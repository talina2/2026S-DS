import argparse
import json
import socket
import random
import struct
import time
import os

"""Statistiken"""
total_multicasts = 0  # Anzahl gezündeter Raketen (dieser Prozess)
round_times = []  # Rundenzeiten (nur Prozess 0)
round_start_ts = None  # Zeitstempel Rundenstart

"""Kommandozeilen argumente"""
# pid; n = Gesamtanzahl; p = Startwahrscheinlichkeit; k = Runden bis Terminierung
parser = argparse.ArgumentParser()
parser.add_argument('pid', type=int)
parser.add_argument('n', type=int)
parser.add_argument('p', type=float)
parser.add_argument('k', type=int)
parser.add_argument('--statsdir', default='.')
args = parser.parse_args()

my_p = args.p

# Nachfolger im Ring
next_pid = (args.pid + 1) % args.n
my_port = 50000 + args.pid
next_port = 50000 + next_pid

"""UDP Socket für Token Weitergabe"""
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("", my_port))
sock.settimeout(10.0)

"""Multicast Socket für Raketen"""
MULTICAST_GROUP = "224.1.1.1"
MULTICAST_PORT = 49999

# Sender: Raketenbenachrichtigung an alle Prozesse
mc_sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
mc_sender.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 32)
mc_sender.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
mc_sender.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton("127.0.0.1"))

# Receiver: Raketenbenachrichtigungen empfangen
mc_receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
mc_receiver.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
mc_receiver.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
mc_receiver.bind(("", MULTICAST_PORT))
mreq = struct.pack("4s4s", socket.inet_aton(MULTICAST_GROUP), socket.inet_aton("127.0.0.1"))
mc_receiver.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
mc_receiver.settimeout(0.002)


def write_stats():
    """ Schreibt Statistiken in JSON (pro Prozess)"""
    stats = {"pid": args.pid, "n": args.n, "multicasts": total_multicasts, }
    if args.pid == 0:
        stats["total_rounds"] = len(round_times)
        stats["round_times_ms"] = round_times
    path = os.path.join(args.statsdir, f"stats_{args.pid}.json")
    with open(path, "w") as f:
        json.dump(stats, f)


def send_token(payload: dict):
    """Sendet Token als UDP Nachricht an Nachfolger"""
    msg = ("TOKEN:" + json.dumps(payload)).encode()
    sock.sendto(msg, ("127.0.0.1", next_port))
    print(f"Token gesendet an Prozess {next_pid}")


def receive_token():
    """Wartet auf UDP Nachricht -> gibt Token als dict zurück oder TERMINATE oder Timeout"""
    try:
        data, _ = sock.recvfrom(1024)
        msg = data.decode()
        if msg.startswith("TOKEN:"):
            payload = json.loads(msg[6:])
            print(f"Token empfangen: {payload}")
            return payload
        elif msg == "TERMINATE":
            print(f"Prozess {args.pid} beendet sich (TERMINATE empfangen)")
            sock.sendto("TERMINATE".encode(), ("127.0.0.1", next_port))
            return "TERMINATE"
    except socket.timeout:
        print("Timeout - kein Token erhalten")
        return None


def read_multicast():
    '''Leert Multicast Puffer; zum loggen'''
    while True:
        try:
            data, _ = mc_receiver.recvfrom(1024)
            msg = data.decode()
            if msg.startswith("ROCKET:"):
                pid_sender = msg.split(":")[1]
                print(f"Rakete von Prozess {pid_sender} empfangen")
        except socket.timeout:
            break


'''Start: Prozess 0 legt Token in Ring'''
# token_hops: Zähler; empty_rounds: leere Runden ohne Rakete, fired_this_lap:. Flag Raketen zündung
if args.pid == 0:
    round_start_ts = time.perf_counter()
    send_token({"token_hops": 0, "empty_rounds": 0, "fired_this_lap": False,})


while True:
    read_multicast()
    payload = receive_token()

    if payload is None:
        continue  #Timeout: nochmal von vorne
    if payload == "TERMINATE":
        write_stats()
        break

    # Rundenzeit der gerade abgeschlossenen Ringrunde messen (Prozess 0)
    if args.pid == 0 and round_start_ts is not None:
        elapsed = time.perf_counter() - round_start_ts
        round_times.append(elapsed * 1000.0)

    print(f"Prozess {args.pid} | Hop {payload['token_hops']} | p={my_p:.4f} | leere Runden: {payload['empty_rounds']}")

    # Rakete zünden mit Wahrscheinlichkeit my_p
    if random.random() < my_p:
        print(f"Prozess {args.pid} zündet Rakete!")
        mc_sender.sendto(f"ROCKET:{args.pid}".encode(), (MULTICAST_GROUP, MULTICAST_PORT))
        total_multicasts += 1
        payload["fired_this_lap"] = True

    # Rundenauswertung, Terminierungsprüfung (Prozess 0)
    if args.pid == 0:
        if payload["fired_this_lap"]:
            payload["empty_rounds"] = 0
        else:
            payload["empty_rounds"] += 1
        payload["fired_this_lap"] = False

        if payload["empty_rounds"] >= args.k:
            print(f"Terminierung: {args.k} leere Ringrunden erreicht")
            sock.sendto("TERMINATE".encode(), ("127.0.0.1", next_port))
            write_stats()
            break

    # Wahrscheinlichkeit halbieren
    my_p = my_p / 2
    payload["token_hops"] += 1

    # Timer nächste Ringrunde (Prozess 0)
    if args.pid == 0:
        round_start_ts = time.perf_counter()

    send_token(payload)

sock.close()
mc_sender.close()
mc_receiver.close()
print(f"Prozess {args.pid} fertig.")
