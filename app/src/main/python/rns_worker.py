import RNS
import LXMF
import threading
import signal
import os
import time
import struct
from RNS.Interfaces.Interface import Interface
from collections import deque

destination = None
lxmf_router = None
reticulum    = None
_rns_started = False
_start_done  = threading.Event()
_start_result = {"addr": None, "error": None}

chat_messages  = []
seen_announces = []
known_identities = {}
contacts = {}

CONTACTS_PATH = "/data/data/com.example.oilpalmharvester/files/contacts.json"

def load_contacts():
    global contacts
    try:
        import json
        if os.path.exists(CONTACTS_PATH):
            with open(CONTACTS_PATH, "r") as f:
                contacts = json.load(f)
    except Exception as e:
        RNS.log(f"Could not load contacts: {e}")
        contacts = {}

def save_contacts():
    try:
        import json
        with open(CONTACTS_PATH, "w") as f:
            json.dump(contacts, f)
    except Exception as e:
        RNS.log(f"Could not save contacts: {e}")

def set_contact(hash_hex, name):
    hash_hex = hash_hex.strip().replace("<","").replace(">","")
    if name.strip():
        contacts[hash_hex] = name.strip()
    else:
        contacts.pop(hash_hex, None)
    save_contacts()
    return "OK"

def get_contact(hash_hex):
    hash_hex = hash_hex.strip().replace("<","").replace(">","")
    return contacts.get(hash_hex, "")

RNS_CONFIG = """
[reticulum]
  enable_transport = True
  share_instance = False
  shared_instance_port = 37428
  instance_control_port = 37429
  panic_on_interface_error = False

[interfaces]

"""

KISS_FEND       = 0xC0
KISS_FESC       = 0xDB
KISS_TFEND      = 0xDC
KISS_TFESC      = 0xDD
CMD_DATA        = 0x00
CMD_FREQUENCY   = 0x01
CMD_BANDWIDTH   = 0x02
CMD_TXPOWER     = 0x03
CMD_SF          = 0x04
CMD_CR          = 0x05
CMD_RADIO_STATE = 0x06
RADIO_STATE_ON  = 0x01

def kiss_escape(data):
    out = []
    for b in data:
        if b == KISS_FEND:
            out += [KISS_FESC, KISS_TFEND]
        elif b == KISS_FESC:
            out += [KISS_FESC, KISS_TFESC]
        else:
            out.append(b)
    return bytes(out)

def kiss_cmd(cmd, data=b""):
    return bytes([KISS_FEND, cmd]) + kiss_escape(data) + bytes([KISS_FEND])

def configure_rnode(socket):
    RNS.log("Configuring RNode radio parameters...")
    socket.write(kiss_cmd(CMD_FREQUENCY, struct.pack(">I", 433025000)))
    time.sleep(0.1)
    socket.write(kiss_cmd(CMD_BANDWIDTH, struct.pack(">I", 31250)))
    time.sleep(0.1)
    socket.write(kiss_cmd(CMD_TXPOWER, bytes([17])))
    time.sleep(0.1)
    socket.write(kiss_cmd(CMD_SF, bytes([8])))
    time.sleep(0.1)
    socket.write(kiss_cmd(CMD_CR, bytes([6])))
    time.sleep(0.1)
    socket.write(kiss_cmd(CMD_RADIO_STATE, bytes([RADIO_STATE_ON])))
    time.sleep(0.5)
    RNS.log("RNode radio configured and ON")

class AndroidBTInterface(Interface):
    BITRATE_GUESS = 1200

    def __init__(self, owner, name, socket):
        super().__init__()
        self.owner                 = owner
        self.name                  = name
        self.rxb                   = 0
        self.txb                   = 0
        self.online                = False
        self.IN                    = True
        self.OUT                   = True
        self.FWD                   = False
        self.RPT                   = False
        self._socket               = socket
        self.bitrate               = self.BITRATE_GUESS
        self.ingress_control       = False
        self.ic_max_held_announces = 0
        self.ic_burst_hold_time    = 0
        self.ic_burst_freq_new     = 0
        self.ic_burst_freq         = 0
        self.announce_cap          = 2
        self.announce_queue        = []
        self.held_announces        = {}
        self.announced_identity    = None
        self.mode                  = Interface.MODE_FULL
        self.oa_freq_deque         = deque(maxlen=16)
        self.ifac_size             = None
        self.ifac_netkey           = None
        self.ifac_key              = None
        self.ifac_identity         = None
        self.ifac_signature        = None
        self.online                = True
        self._kiss_buf             = []
        self._in_frame             = False
        self._escape               = False
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _read_loop(self):
        while self.online:
            try:
                data = self._socket.read(512)
                if data and len(data) > 0:
                    self._parse_kiss(data)
            except Exception as e:
                RNS.log(f"BT read error: {e}")
                self.online = False

    def _parse_kiss(self, data):
        for byte in data:
            if byte == KISS_FEND:
                if self._in_frame and len(self._kiss_buf) > 1:
                    if self._kiss_buf[0] == CMD_DATA:
                        pkt = bytes(self._kiss_buf[1:])
                        self.rxb += len(pkt)
                        self.owner.inbound(pkt, self)
                self._kiss_buf = []
                self._in_frame = True
                self._escape   = False
            elif self._in_frame:
                if byte == KISS_FESC:
                    self._escape = True
                elif self._escape:
                    self._escape = False
                    if byte == KISS_TFEND:
                        self._kiss_buf.append(KISS_FEND)
                    elif byte == KISS_TFESC:
                        self._kiss_buf.append(KISS_FESC)
                else:
                    self._kiss_buf.append(byte)

    def process_outgoing(self, data):
        try:
            self._socket.write(kiss_cmd(CMD_DATA, data))
            self.txb += len(data)
        except Exception as e:
            RNS.log(f"BT write error: {e}")

def message_received(message):
    sender = RNS.prettyhexrep(message.source_hash)
    text   = message.content_as_string()
    ts     = time.strftime("%H:%M:%S")
    entry  = {"from": sender, "text": text, "ts": ts, "direction": "in"}
    chat_messages.append(entry)

def announce_received(destination_hash, announced_identity, app_data):
    global known_identities
    hash_str = RNS.prettyhexrep(destination_hash)
    name = ""
    if app_data:
        try:
            name = app_data.decode("utf-8")
        except:
            name = str(app_data)
    ts = time.strftime("%H:%M:%S")
    if announced_identity is not None:
        known_identities[hash_str] = announced_identity
    entry = {"hash": hash_str, "name": name, "ts": ts}
    for i, a in enumerate(seen_announces):
        if a["hash"] == hash_str:
            seen_announces[i] = entry
            return
    seen_announces.append(entry)

class AnnounceHandler:
    aspect_filter = "lxmf.delivery"
    def received_announce(self, destination_hash, announced_identity, app_data):
        announce_received(destination_hash, announced_identity, app_data)

def _noop_signal(sig, handler):
    pass

def _rns_main(bt_socket_wrapper):
    global destination, lxmf_router, reticulum
    try:
        configure_rnode(bt_socket_wrapper)
        configdir = "/data/data/com.example.oilpalmharvester/files/.reticulum"
        os.makedirs(configdir, exist_ok=True)
        with open(os.path.join(configdir, "config"), "w") as f:
            f.write(RNS_CONFIG)

        original_signal = signal.signal
        signal.signal  = _noop_signal
        load_contacts()
        reticulum = RNS.Reticulum(configdir=configdir, loglevel=RNS.LOG_DEBUG)

        iface = AndroidBTInterface(RNS.Transport, "RNodeBT", bt_socket_wrapper)
        RNS.Transport.interfaces.append(iface)

        identity_path = "/data/data/com.example.oilpalmharvester/files/identity"
        identity = None
        if os.path.exists(identity_path):
            try:
                identity = RNS.Identity.from_file(identity_path)
            except:
                identity = None
        if identity is None:
            identity = RNS.Identity()
            try:
                identity.to_file(identity_path)
            except Exception as e:
                RNS.log(f"WARNING: Could not save identity: {e}")

        lxmf_router = LXMF.LXMRouter(
            storagepath="/data/data/com.example.oilpalmharvester/files/lxmf",
            autopeer=True)

        signal.signal = original_signal

        destination = lxmf_router.register_delivery_identity(
            identity, display_name="OilPalm Harvester")
        lxmf_router.register_delivery_callback(message_received)
        RNS.Transport.register_announce_handler(AnnounceHandler())
        destination.announce()

        addr = RNS.prettyhexrep(destination.hash)
        RNS.log(f"LXMF address: {addr}")
        _start_result["addr"] = addr

    except Exception as e:
        import traceback
        _start_result["error"] = str(e)
        RNS.log(f"RNS start error: {e}\n{traceback.format_exc()}")
    finally:
        _start_done.set()

def start(bt_socket_wrapper):
    global _rns_started
    if _rns_started:
        return RNS.prettyhexrep(destination.hash) if destination else "Error: no address"
    _rns_started = True
    _start_done.clear()
    _start_result["addr"] = None
    _start_result["error"] = None
    threading.Thread(target=_rns_main, args=(bt_socket_wrapper,), daemon=True).start()
    _start_done.wait(timeout=30)
    if _start_result["error"]:
        return f"Error: {_start_result['error']}"
    return _start_result["addr"] or "Timeout"

def _send_lxmf(dest_hash_hex, title, body):
    """Internal: send one LXMF message. Returns True on success."""
    global lxmf_router, destination, known_identities
    dest_hash_hex = dest_hash_hex.strip()
    dest_hash     = bytes.fromhex(dest_hash_hex)

    recalled = known_identities.get(dest_hash_hex) or RNS.Identity.recall(dest_hash)
    if recalled is None:
        RNS.Transport.request_path(dest_hash)
        for _ in range(15):
            time.sleep(2)
            recalled = known_identities.get(dest_hash_hex) or RNS.Identity.recall(dest_hash)
            if recalled:
                break
    if recalled is None:
        return False, "No identity known for destination"

    lxmf_dest = RNS.Destination(
        recalled, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")

    msg = LXMF.LXMessage(
        lxmf_dest, destination, body, title=title,
        desired_method=LXMF.LXMessage.DIRECT)

    delivered = threading.Event()
    result    = {"ok": False}

    def on_delivered(m):
        result["ok"] = True
        delivered.set()

    def on_failed(m):
        delivered.set()

    msg.register_delivery_callback(on_delivered)
    msg.register_failed_callback(on_failed)
    lxmf_router.handle_outbound(msg)
    delivered.wait(timeout=60)
    return result["ok"], "OK" if result["ok"] else "Delivery failed or timed out"

def send_csv(dest_hash_hex, csv_text, filename):
    """Send CSV records as an LXMF message."""
    if not lxmf_router or not destination:
        return "Not connected"
    ok, msg = _send_lxmf(dest_hash_hex, f"HARVEST_CSV:{filename}", csv_text)
    return "OK" if ok else f"Error: {msg}"

def send_photo(dest_hash_hex, photo_path, record_id):
    """Send a photo as base64-encoded LXMF message."""
    if not lxmf_router or not destination:
        return "Not connected"
    try:
        import base64
        with open(photo_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode("utf-8")
        filename = os.path.basename(photo_path)
        body = f"HARVEST_PHOTO:{record_id}:{filename}:{b64}"
        ok, msg = _send_lxmf(dest_hash_hex, f"HARVEST_PHOTO:{record_id}", body)
        return "OK" if ok else f"Error: {msg}"
    except Exception as e:
        return f"Error: {e}"

def send_message(dest_hash_hex, text):
    if not lxmf_router or not destination:
        return "Not connected"
    ok, msg = _send_lxmf(dest_hash_hex, "", text)
    if ok:
        ts = time.strftime("%H:%M:%S")
        chat_messages.append({"from": "me", "text": text, "ts": ts, "direction": "out"})
        return "Sent!"
    return f"Error: {msg}"

def get_messages():
    result = []
    for m in chat_messages:
        entry = dict(m)
        h = entry.get("from","").replace("<","").replace(">","")
        entry["display_from"] = contacts.get(h, entry.get("from",""))
        result.append(entry)
    return result

def get_announces():
    result = []
    for a in seen_announces:
        entry = dict(a)
        h = entry.get("hash","").replace("<","").replace(">","")
        entry["display"] = contacts.get(h, entry.get("name",""))
        result.append(entry)
    return result

def get_address():
    return RNS.prettyhexrep(destination.hash) if destination else "Not initialized"


