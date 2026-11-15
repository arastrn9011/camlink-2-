"""CamLink PC uygulaması: telefonu USB veya WiFi ile web kamerası yapar."""
import json, os, socket, subprocess, sys, threading, time
from io import BytesIO
import tkinter as tk
from tkinter import ttk, messagebox
import av
import numpy as np
from PIL import Image, ImageTk
try:
    import pyvirtualcam
except Exception:
    pyvirtualcam = None

PORT = 5000
APPDIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "CamLink")
CFG = os.path.join(APPDIR, "settings.json")


class Stop(Exception):
    pass


def load_cfg():
    try:
        with open(CFG, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_cfg(d):
    try:
        os.makedirs(APPDIR, exist_ok=True)
        with open(CFG, "w", encoding="utf-8") as f:
            json.dump(d, f)
    except Exception:
        pass


def adb_path():
    base = getattr(sys, "_MEIPASS", None)
    if base:
        p = os.path.join(base, "platform-tools", "adb.exe")
        if os.path.exists(p):
            return p
    return "adb"


def run_adb(*args):
    return subprocess.run([adb_path(), *args], capture_output=True, text=True, timeout=20,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def usb_forward():
    try:
        r = run_adb("devices")
    except FileNotFoundError:
        raise RuntimeError("adb bulunamadı. Platform-tools kur veya exe sürümünü kullan")
    devs = [l.split("\t") for l in r.stdout.splitlines() if "\t" in l]
    if not any(len(d) == 2 and d[1].strip() == "device" for d in devs):
        if any(len(d) == 2 and "unauthorized" in d[1] for d in devs):
            raise RuntimeError("Telefonda 'USB hata ayıklamaya izin ver' sorusunu onayla")
        raise RuntimeError("USB'de telefon yok. Kabloyu ve 'USB hata ayıklama'yı kontrol et")
    r = run_adb("forward", f"tcp:{PORT}", f"tcp:{PORT}")
    if r.returncode != 0:
        raise RuntimeError("adb forward başarısız: " + r.stderr.strip())


class Receiver(threading.Thread):
    def __init__(self, app, usb, host):
        super().__init__(daemon=True)
        self.app, self.usb, self.host = app, usb, host
        self.stop_ev = threading.Event()

    def run(self):
        while not self.stop_ev.is_set():
            try:
                self.session()
            except Stop:
                return
            except Exception as e:
                self.app.status = str(e) or e.__class__.__name__
            self.app.info = ""
            for _ in range(15):
                if self.stop_ev.wait(0.1):
                    return

    def recv(self, sock, n):
        buf = bytearray(n)
        mv = memoryview(buf)
        got = 0
        while got < n:
            if self.stop_ev.is_set():
                raise Stop()
            try:
                k = sock.recv_into(mv[got:], n - got)
            except socket.timeout:
                continue
            if k == 0:
                raise ConnectionError("Bağlantı kapandı. Telefonda CamLink açık mı? Başlat'a bastın mı?")
            got += k
        return bytes(buf)

    def make_decoder(self):
        dec = av.CodecContext.create("h264", "r")
        try:
            dec.thread_type = "SLICE"
        except Exception:
            pass
        return dec

    def session(self):
        host = self.host
        if self.usb:
            self.app.status = "USB: telefon aranıyor..."
            usb_forward()
            host = "127.0.0.1"
        self.app.status = "Telefona bağlanılıyor..."
        sock = socket.create_connection((host, PORT), timeout=5)
        try:
            sock.settimeout(1.0)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            self.app.status = "Bağlandı. Telefonda Başlat'a bas."
            mode, dec, cfg, got_key = None, None, b"", False
            while True:
                hdr = self.recv(sock, 5)
                typ = hdr[0]
                n = int.from_bytes(hdr[1:5], "big")
                data = self.recv(sock, n) if n else b""
                self.app.bytes += n + 5
                if typ == 9:
                    mode, w, h, fps = data.decode().split(";")
                    dec, cfg, got_key = None, b"", False
                    if mode == "h264":
                        dec = self.make_decoder()
                    self.app.info = f"{mode.upper()} {w}x{h}"
                    self.app.status = "Yayın alınıyor"
                elif typ == 8:
                    mode = None
                    self.app.info = ""
                    self.app.status = "Telefon yayını durdurdu. Telefonda Başlat'a bas."
                elif typ == 0:
                    cfg = data
                elif typ in (1, 3) and mode == "h264" and dec is not None:
                    if typ == 3:
                        payload, got_key = cfg + data, True
                    elif got_key:
                        payload = data
                    else:
                        continue
                    try:
                        for fr in dec.decode(av.Packet(payload)):
                            self.app.push(fr.to_image())
                    except Exception:
                        dec, got_key = self.make_decoder(), False
                elif typ == 2 and mode == "mjpeg":
                    try:
                        img = Image.open(BytesIO(data))
                        img.load()
                        self.app.push(img.convert("RGB"))
                    except Exception:
                        pass
        finally:
            try:
                sock.close()
            except Exception:
                pass


class App:
    ROT = {90: Image.Transpose.ROTATE_270, 180: Image.Transpose.ROTATE_180, 270: Image.Transpose.ROTATE_90}

    def __init__(self):
        c = load_cfg()
        self.status, self.info = "Hazır", ""
        self.bytes = 0
        self.frames = 0
        self.fid = self.shown = 0
        self.latest = None
        self.out = None
        self.rx = None
        self.vcam = None
        self.vcam_size = None
        self.photo = None
        self._last = (time.time(), 0, 0)

        r = self.root = tk.Tk()
        r.title("CamLink")
        r.geometry("980x640")
        r.minsize(640, 420)
        self.mode = tk.StringVar(value=c.get("mode", "usb"))
        self.ip = tk.StringVar(value=c.get("ip", ""))
        self.rot = tk.IntVar(value=c.get("rot", 0))
        self.mirror = tk.BooleanVar(value=c.get("mirror", False))
        self.vcam_on = tk.BooleanVar(value=False)

        top = ttk.Frame(r, padding=6)
        top.pack(fill="x")
        ttk.Radiobutton(top, text="USB", variable=self.mode, value="usb", command=self.upd).pack(side="left")
        ttk.Radiobutton(top, text="WiFi", variable=self.mode, value="wifi", command=self.upd).pack(side="left", padx=(8, 0))
        ttk.Label(top, text="Telefon IP:").pack(side="left", padx=(12, 2))
        self.ip_entry = ttk.Entry(top, width=16, textvariable=self.ip)
        self.ip_entry.pack(side="left")
        self.btn = ttk.Button(top, text="Bağlan", command=self.toggle)
        self.btn.pack(side="left", padx=10)

        bar = ttk.Frame(r, padding=(6, 0, 6, 6))
        bar.pack(fill="x")
        ttk.Button(bar, text="Döndür 90°", command=self.rotate).pack(side="left")
        self.rot_lbl = ttk.Label(bar, text="")
        self.rot_lbl.pack(side="left", padx=(4, 12))
        ttk.Checkbutton(bar, text="Aynala", variable=self.mirror).pack(side="left")
        self.vc = ttk.Checkbutton(bar, text="Sanal kamera (Zoom/Discord/OBS)", variable=self.vcam_on)
        self.vc.pack(side="left", padx=12)
        ttk.Button(bar, text="Fotoğraf çek", command=self.snapshot).pack(side="left")
        ttk.Button(bar, text="Tam ekran (F11)", command=self.fullscreen).pack(side="left", padx=12)

        self.canvas = tk.Canvas(r, bg="black", highlightthickness=0)
        self.canvas.pack(fill="both", expand=True)
        self.cimg = self.canvas.create_image(0, 0, anchor="center")
        self.hint = self.canvas.create_text(0, 0, fill="#aaaaaa", font=("Segoe UI", 12), justify="center",
            text="1) Telefonda CamLink'i aç ve Başlat'a bas\n2) Burada Bağlan'a bas")
        self.stat = ttk.Label(r, text="", anchor="w", padding=4)
        self.stat.pack(fill="x")

        r.bind("<F11>", lambda e: self.fullscreen())
        r.bind("<Escape>", lambda e: r.attributes("-fullscreen", False))
        r.protocol("WM_DELETE_WINDOW", self.close)
        self.upd()
        self.rot_lbl.config(text=f"{self.rot.get()}°")
        self.tick()
        self.stats_tick()
        r.mainloop()

    def upd(self):
        self.ip_entry.config(state="normal" if self.mode.get() == "wifi" else "disabled")

    def rotate(self):
        self.rot.set((self.rot.get() + 90) % 360)
        self.rot_lbl.config(text=f"{self.rot.get()}°")

    def fullscreen(self):
        self.root.attributes("-fullscreen", not self.root.attributes("-fullscreen"))

    def toggle(self):
        if self.rx is not None:
            self.rx.stop_ev.set()
            self.rx = None
            self.btn.config(text="Bağlan")
            self.status, self.info = "Bağlantı kesildi", ""
            return
        usb = self.mode.get() == "usb"
        host = self.ip.get().strip()
        if not usb and not host:
            messagebox.showinfo("CamLink", "Telefonda yazan WiFi IP adresini gir.")
            return
        self.rx = Receiver(self, usb, host)
        self.rx.start()
        self.btn.config(text="Bağlantıyı kes")

    def push(self, img):
        self.latest = img
        self.fid += 1
        self.frames += 1

    def send_vcam(self, img):
        if pyvirtualcam is None:
            self.vcam_on.set(False)
            messagebox.showerror("CamLink", "Sanal kamera modülü yüklü değil.")
            return
        size = img.size
        if self.vcam is None or self.vcam_size != size:
            if self.vcam is not None:
                try:
                    self.vcam.close()
                except Exception:
                    pass
            try:
                self.vcam = pyvirtualcam.Camera(size[0], size[1], 30, fmt=pyvirtualcam.PixelFormat.RGB)
                self.vcam_size = size
            except Exception as e:
                self.vcam = None
                self.vcam_on.set(False)
                messagebox.showerror("CamLink", "Sanal kamera açılamadı.\nOBS Studio'yu (obsproject.com) kur, "
                                     "bir kez aç, kapat, sonra tekrar dene.\n\n" + str(e))
                return
        self.vcam.send(np.array(img))

    def snapshot(self):
        if self.out is None:
            self.status = "Henüz görüntü yok"
            return
        d = os.path.join(os.path.expanduser("~"), "Pictures", "CamLink")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, time.strftime("camlink_%Y%m%d_%H%M%S.png"))
        self.out.save(p)
        self.status = "Fotoğraf kaydedildi: " + p

    def tick(self):
        try:
            if self.latest is not None and self.fid != self.shown:
                self.shown = self.fid
                img = self.latest
                r = self.rot.get()
                if r:
                    img = img.transpose(self.ROT[r])
                if self.mirror.get():
                    img = img.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
                self.out = img
                if self.vcam_on.get():
                    self.send_vcam(img)
                elif self.vcam is not None:
                    try:
                        self.vcam.close()
                    except Exception:
                        pass
                    self.vcam = None
                cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
                if cw > 10 and ch > 10:
                    s = min(cw / img.width, ch / img.height)
                    view = img.resize((max(1, int(img.width * s)), max(1, int(img.height * s))), Image.Resampling.BILINEAR)
                    self.photo = ImageTk.PhotoImage(view)
                    self.canvas.itemconfig(self.cimg, image=self.photo)
                    self.canvas.coords(self.cimg, cw // 2, ch // 2)
                    self.canvas.itemconfig(self.hint, state="hidden")
            else:
                cw, ch = self.canvas.winfo_width(), self.canvas.winfo_height()
                self.canvas.coords(self.hint, cw // 2, ch // 2)
        except Exception as e:
            self.status = f"Gösterim hatası: {e}"
        self.root.after(8, self.tick)

    def stats_tick(self):
        now = time.time()
        t0, f0, b0 = self._last
        dt = max(now - t0, 0.001)
        fps = (self.frames - f0) / dt
        mbit = (self.bytes - b0) * 8 / dt / 1e6
        self._last = (now, self.frames, self.bytes)
        extra = f"  |  {self.info}  {fps:.0f} fps  {mbit:.1f} Mbit/s" if self.info else ""
        self.stat.config(text=self.status + extra)
        self.root.after(1000, self.stats_tick)

    def close(self):
        save_cfg({"mode": self.mode.get(), "ip": self.ip.get(), "rot": self.rot.get(), "mirror": self.mirror.get()})
        if self.rx is not None:
            self.rx.stop_ev.set()
        if self.vcam is not None:
            try:
                self.vcam.close()
            except Exception:
                pass
        self.root.destroy()


if __name__ == "__main__":
    App()
