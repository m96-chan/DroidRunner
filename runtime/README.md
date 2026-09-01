# Runtime bundle contract

The APK has no Termux dependency. It downloads one signed-by-hash bundle on first setup.

Expected layout:

```text
bin/proot
rootfs/bin/sh
rootfs/usr/bin/env
home/runner/config.sh
home/runner/run.sh
home/runner/bin/Runner.Listener
```

Build the bundle on an ARM64 Linux host. Put a static Android-compatible `proot` at
`bin/proot`, an ARM64 glibc rootfs at `rootfs`, and the official Linux ARM64 GitHub
Actions runner at `home/runner`. Package them with:

```bash
tar -C bundle -czf droidrunner-runtime-arm64.tar.gz bin rootfs home
sha256sum droidrunner-runtime-arm64.tar.gz
```

Publish the archive and a manifest matching `runtime-manifest.example.json`.
The APK refuses a bundle whose SHA-256 does not match.
