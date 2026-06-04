import argparse
import json
import socket
import random
import struct
import time
import os
import sys

"""Statistiken"""
total_multicasts = 0  # Anzahl gezündeter Raketen (dieser Prozess)
round_times = []  # Rundenzeiten (nur Prozess 0)
round_start_ts = None  # Zeitstempel Rundenstart

"""Kommandozeilen argumente"""
# pid; n = Gesamtanzahl; p = Startwahrscheinlichkeit; k = Runden bis Terminierung
parser = argparse.ArgumentParser()
parser.add_argument('--conf', default='ring.conf', help='Pfad zur Konfigurationsdatei')
parser.add_argument('--pid', type=int, required=True, help='Eigene Prozess-ID')
parser.add_argument('--p', type=float, default=0.9, help='Startzündwahrscheinlichkeit')
parser.add_argument('--k', type=int, default=3, help='Leere Runden bis Terminierung')
parser.add_argument('--statsdir', default='.', help='Ablageort der Statistik-JSON')
parser.add_argument('--unicast', action='store_true', help='Raketen per Unicast statt Multicast (Fallback)')
args = parser.parse_args()

my_p = args.p

"""Konfigurationsdatei einlesen"""
with open(args.conf) as f:
    ring_conf = json.load(f)

ring_conf.sort(key=lambda x: x['pid'])
n = len(ring_conf)

my_entry = ring_conf[args.pid]
next_entry = ring_conf[(args.pid + 1) % n]  # nachfolger im Ring
next_pid = next_entry['pid']
next_host = next_entry['host']
next_port = next_entry['port']
my_host = my_entry['host']
my_port = my_entry['port']

raketen_modus = "Unicast" if args.unicast else "Multicast"
print(f"[pid={args.pid}] Eigene Adresse: {my_host}:{my_port}")
print(f"[pid={args.pid}] Naechster: {next_host}:{next_port} (pid={next_pid})")
print(f"[pid={args.pid}] Ringgroesse: {n}, Raketen: {raketen_modus}")

"""UDP Socket für Token Weitergabe"""
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("", my_port))
sock.settimeout(30.0)

"""Multicast Socket für Raketen"""
MULTICAST_GROUP = "224.1.1.1"
MULTICAST_PORT = 49999

mc_sender = None
mc_receiver = None

if not args.unicast:
    # Sender: Raketenbenachrichtigung an alle Prozesse
    mc_sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    mc_sender.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 8)
    mc_sender.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 1)
    mc_sender.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(my_host))

    # Receiver: Raketenbenachrichtigungen empfangen
    mc_receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    mc_receiver.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if hasattr(socket, 'SO_REUSEPORT'):
        mc_receiver.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    mc_receiver.bind(("", MULTICAST_PORT))
    mreq = struct.pack("4s4s", socket.inet_aton(MULTICAST_GROUP), socket.inet_aton(my_host))
    mc_receiver.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
    mc_receiver.settimeout(0.05)

    print(f"[pid={args.pid}] Multicast-Gruppe {MULTICAST_GROUP}:{MULTICAST_PORT} beigetreten ({my_host})")


def write_stats():
    """Schreibt Statistiken in JSON (pro Prozess)."""
    stats = {"pid": args.pid, "n": n, "multicasts": total_multicasts}
    if args.pid == 0:
        stats["total_rounds"] = len(round_times)
        stats["round_times_ms"] = round_times
    path = os.path.join(args.statsdir, f"stats_{args.pid}.json")
    with open(path, "w") as f:
        json.dump(stats, f)
    print(f"[pid={args.pid}] Statistik geschrieben: {path}")


def send_token(payload: dict):
    """Sendet Token als UDP Nachricht an Nachfolger"""
    msg = ("TOKEN:" + json.dumps(payload)).encode()
    sock.sendto(msg, (next_host, next_port))
    print(f"[pid={args.pid}] Token → pid={next_pid}")


def receive_token():
    """Wartet auf UDP Nachricht -> gibt Token als dict zurück oder TERMINATE oder Timeout"""
    while True:
        try:
            data, addr = sock.recvfrom(4096)
            msg = data.decode()
            if msg.startswith("TOKEN:"):
                payload = json.loads(msg[6:])
                print(f"[pid={args.pid}] Token empfangen von {addr}")
                return payload
            elif msg == "TERMINATE":
                print(f"[pid={args.pid}] TERMINATE empfangen; leite weiter")
                sock.sendto("TERMINATE".encode(), (next_host, next_port))
                return "TERMINATE"
            elif msg.startswith("ROCKET:"):
                sender_pid = msg.split(":")[1]
                print(f"[pid={args.pid}] Rakete von pid={sender_pid} (Unicast)")
                continue
        except socket.timeout:
            print(f"[pid={args.pid}] Timeout – kein Token erhalten")
            return None


def send_rocket():
    """Rakete senden: Multicast (Standard) oder Unicast (Fallback)"""
    global total_multicasts
    msg = f"ROCKET:{args.pid}".encode()
    if args.unicast:
        for entry in ring_conf:
            if entry['pid'] != args.pid:
                sock.sendto(msg, (entry['host'], entry['port']))
    else:
        mc_sender.sendto(msg, (MULTICAST_GROUP, MULTICAST_PORT))

    total_multicasts += 1
    print(f"[pid={args.pid}] Rakete gezündet! ({raketen_modus})")


def read_multicast():
    """Leert Multicast Puffer; zum loggen"""
    if not mc_receiver:
        return
    while True:
        try:
            data, _ = mc_receiver.recvfrom(1024)
            msg = data.decode()
            if msg.startswith("ROCKET:"):
                sender_pid = msg.split(":")[1]
                print(f"[pid={args.pid}] Rakete von pid={sender_pid} (Multicast)")
        except socket.timeout:
            break


'''Start: Prozess 0 legt Token in Ring'''
# token_hops: Zähler; empty_rounds: leere Runden ohne Rakete, fired_this_lap:. Flag Raketen zündung
if args.pid == 0:
    round_start_ts = time.perf_counter()
    send_token({"token_hops": 0, "empty_rounds": 0, "fired_this_lap": False})

while True:
    read_multicast()
    payload = receive_token()

    if payload is None:
        continue
    if payload == "TERMINATE":
        write_stats()
        break

    # Rundenzeit der gerade abgeschlossenen Ringrunde messen (Prozess 0)
    if args.pid == 0 and round_start_ts is not None:
        elapsed = time.perf_counter() - round_start_ts
        round_times.append(elapsed * 1000.0)

    print(f"[pid={args.pid}] Hop {payload['token_hops']} | p={my_p:.4f} | leere Runden: {payload['empty_rounds']}")

    if random.random() < my_p:
        send_rocket()
        payload["fired_this_lap"] = True

    if args.pid == 0:
        if payload["fired_this_lap"]:
            payload["empty_rounds"] = 0
        else:
            payload["empty_rounds"] += 1
        payload["fired_this_lap"] = False

        if payload["empty_rounds"] >= args.k:
            print(f"[pid={args.pid}] Terminierung: {args.k} leere Runden erreicht")
            sock.sendto("TERMINATE".encode(), (next_host, next_port))
            write_stats()
            break

    # Zündwahrscheinlichkeit halbieren
    my_p /= 2
    payload["token_hops"] += 1

    if args.pid == 0:
        round_start_ts = time.perf_counter()

    send_token(payload)

sock.close()
if mc_sender: mc_sender.close()
if mc_receiver: mc_receiver.close()
print(f"[pid={args.pid}] Fertig.")
