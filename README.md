# TimerRoot

Android front end for **CVE-2026-98260** (POSIX CPU timer use-after-free), the
same class of bug as CVE-2026-64560. One tap gives you a root shell and then
late-loads **KernelSU** (`u:r:ksu:s0`).

The exploit itself is *not* included here. This repository is the app shell
around it: UI, state detection and the Shizuku plumbing needed to run a
shell-domain exploit from an ordinary app.

## Why the app needs Shizuku

An app runs as `untrusted_app` and cannot read `/sys/kernel/tracing`, which the
exploit needs for its leak and trigger. TimerRoot therefore starts a **Shizuku
user service**, which Shizuku runs as the shell user (`uid 2000`,
`u:r:shell:s0`). The exploit binary executes inside that process, so it gets
the same environment it would have from `adb shell`.

```
MainActivity (untrusted_app)
  └─ Shizuku user service (uid 2000, u:r:shell:s0)
       ├─ extracts assets/timer to /data/local/tmp/timer
       └─ runs it; on success keeps /system/bin/sh alive
```

## Requirements

- Android 12+ (`minSdk 30`), arm64
- [Shizuku](https://shizuku.rikka.app/) running (start it from the Shizuku app
  via wireless debugging, or over ADB)
- [KernelSU](https://github.com/tiann/KernelSU) manager installed, for the
  `Load KernelSU` path
- A kernel build the exploit was ported to. The upstream binary targets
  `6.12.69-android16-6-g242e1a07f878-ab15865446-4k`; see below.

## Getting the exploit binary

TimerRoot expects the compiled exploit at `app/src/main/assets/timer`:

```bash
git clone https://github.com/YuKongA/CVE-2026-98260
cp CVE-2026-98260/timer/timer app/src/main/assets/timer
```

or

```powershell
scripts/fetch-timer.ps1
```

The binary is MIT licensed by its authors; only use it on hardware you own, and
only when your kernel matches the build it was ported to.

## Build

### Android Studio / Gradle

Open the project and build, or:

```bash
./gradlew assembleDebug
```

The project needs the Android SDK with platform 36 and build-tools 36. The
Shizuku API is pulled from Maven (`dev.rikka.shizuku:api`).

### Without Gradle

`scripts/build.ps1` builds and signs an APK with just the SDK, `javac`, `d8`,
`aapt2` and `apksigner`. It vendors nothing: it compiles the app together with
the Shizuku API sources, so clone those first:

```powershell
git clone https://github.com/RikkaApps/Shizuku-API
scripts/build.ps1 -Sdk "C:\Users\<you>\AppData\Local\Android\Sdk"
```

## Usage

### 1. Start Shizuku

Shizuku has to be running before TimerRoot can do anything: it is the process
that provides the shell user the exploit needs. Shizuku stops on every reboot,
so this is the first step each time.

**Wireless debugging (no PC after the first pairing)**

1. Enable developer options: Settings -> About device -> tap *Build number* 7x.
2. Settings -> System -> Developer options -> **Wireless debugging** -> on.
3. Open the Shizuku app -> **Start** -> *Start via wireless debugging*.
4. Allow the notification, then in *Pair device with pairing code* enter the
   6-digit code shown on the phone.
5. Shizuku reports "Shizuku is running" (the notification says the server is up).

**Over USB**

With the phone connected and USB debugging authorised:

```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

Newer Shizuku releases may not ship that script any more; if it is missing, use
the wireless debugging route above.

Check that the server is up:

```bash
adb shell ps -A | grep shizuku_server
# shell  12345  1  ...  shizuku_server
```

`ps` showing only `moe.shizuku.privileged.api` means the manager app is open but
the server is **not** running; the exploit cannot start until it is.

### 2. First launch

1. Open TimerRoot.
2. When Shizuku asks *Allow TimerRoot to access Shizuku?*, choose
   **Allow all the time**.
3. The card turns **Ready** (Shizuku connected, no root yet). If it still says
   `Not connected`, press `Shizuku` in the tools row to request again.

### 3. Prepare

`Prepare` extracts `assets/timer` to `/data/local/tmp/timer`:

```
installed 307192 bytes
```

### 4. Get root shell

One tap. The app escalates, keeps a `uid=0` shell inside the service, and then
starts the KernelSU late-load automatically:

```
[child] woke uid=0
root shell ready - loading KernelSU...
Loading KernelSU... 12s
KSU_LOADED
```

The card goes **Root shell** -> **Jailbreak mode** (`u:r:ksu:s0`). If the
KernelSU stage loses its race, the card turns red with `KernelSU failed`, and
you should reboot before retrying.

### 5. Reboot

Use the `Reboot` pill. It goes through the service (`svc power reboot`), so it
works even when no root shell is open yet. Rebooting is also the only clean way
to remove parked processes and to unload KernelSU.

After rebooting, start again from step 1: Shizuku is stopped and KernelSU is
gone.

The status card reflects what is actually live, detected by running
`su -c id` and checking for `u:r:ksu:s0`:

| card | meaning |
|---|---|
| Not connected | Shizuku is not running / not bound |
| Ready | Shizuku connected, no root yet |
| Root shell | `uid=0`, `u:r:shell:s0` |
| Jailbreak mode | KernelSU loaded, `su` works in `u:r:ksu:s0` |


## Safety

This is a kernel exploit. Read this before pressing anything.

- **Do not kill a parked process.** The exploit keeps a process alive with the
  fake credential page pinned in socket buffers. Reaping it can panic the
  kernel. Reboot is the only clean way to remove it.
- **A reboot clears everything**: KernelSU, the root shell and the parked
  processes. That is the intended way to stop.
- **SELinux is left permissive** until the next reboot.
- A failed attempt leaves a stale timerqueue node; other apps may crash until
  you reboot.
- Only use this on a device you own or are explicitly allowed to modify.

## Layout

```
app/src/main/java/com/kpgc/timerroot/MainActivity.java   UI and state
app/src/main/java/com/kpgc/timerroot/UserService.java    runs as the shell user
app/src/main/aidl/com/kpgc/timerroot/IUserService.aidl   service interface
app/src/main/assets/timer                                exploit binary (not in git)
scripts/build.ps1                                        Gradle-free build
```

## Credits

- Exploit: [YuKongA/CVE-2026-98260](https://github.com/YuKongA/CVE-2026-98260)
  (MIT), based on work by woshigenm and the other 98260 ports.
- [Shizuku](https://github.com/RikkaApps/Shizuku) and
  [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (Apache-2.0) by Rikka.
- KernelSU (GPL-2.0) for the late-load path.

## License

MIT for the app code in this repository. The exploit binary and the Shizuku API
keep their own licenses.
