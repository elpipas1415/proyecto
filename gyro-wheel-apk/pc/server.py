"""Gyro Wheel - receptor para PC (Windows).
Recibe paquetes UDP de la app y los convierte en un control Xbox 360 virtual."""
import socket

import vgamepad as vg

PORT = 5005
pad = vg.VX360Gamepad()
UP = vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER
DOWN = vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def neutral():
    pad.left_joystick_float(x_value_float=0.0, y_value_float=0.0)
    pad.left_trigger_float(value_float=0.0)
    pad.right_trigger_float(value_float=0.0)
    pad.release_button(button=UP)
    pad.release_button(button=DOWN)
    pad.update()


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", PORT))
sock.settimeout(0.5)
neutral()
print(f"\nEscribe esta IP en la app: {local_ip()}   (puerto {PORT})\n")

idle = True
while True:
    try:
        data, _ = sock.recvfrom(256)
    except socket.timeout:
        if not idle:          # si se corta la señal, soltar todo
            neutral()
            idle = True
            print("Sin señal")
        continue
    try:
        s, t, b, u, d = data.decode().split(",")
        pad.left_joystick_float(x_value_float=clamp(float(s), -1, 1), y_value_float=0.0)
        pad.right_trigger_float(value_float=clamp(float(t), 0, 1))
        pad.left_trigger_float(value_float=clamp(float(b), 0, 1))
        (pad.press_button if u == "1" else pad.release_button)(button=UP)
        (pad.press_button if d.strip() == "1" else pad.release_button)(button=DOWN)
        pad.update()
        if idle:
            print("Celular conectado")
        idle = False
    except ValueError:
        pass
