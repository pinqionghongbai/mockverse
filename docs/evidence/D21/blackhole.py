"""D21 三查：假死服务——接受 TCP 连接但永不响应。

用途：验证 ResilientMcpToolset 的 loadTimeout 在"服务假死"（而非"连接拒绝"）时是否生效。
用法：PYTHONUTF8=1 python -I blackhole.py 18084
"""
import socket
import sys
import threading
import time

port = int(sys.argv[1]) if len(sys.argv) > 1 else 18084
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(50)
print("blackhole listening on %d (accepts, never responds)" % port, flush=True)

held = []


def hold(conn):
    # 一直握着连接，一个字节都不回
    held.append(conn)
    while True:
        time.sleep(3600)


while True:
    conn, addr = srv.accept()
    print("accepted %s" % (addr,), flush=True)
    threading.Thread(target=hold, args=(conn,), daemon=True).start()
