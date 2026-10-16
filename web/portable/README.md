# The Hand Portable — Windows x64

Release r13 packages the web app as one self-extracting Windows 10/11 x64 executable. It includes:

- Node.js runtime
- yt-dlp
- FFmpeg and FFprobe
- The Hand local server and browser UI

No installation, Python, Node, FFmpeg, browser extension, or Docker setup is required. Internet access is still required to inspect and download media.

## Runtime behavior

1. The executable extracts its signed-by-checksum components into a random Windows temporary directory.
2. It starts the server on a random `127.0.0.1` port and opens the default browser.
3. The browser saves completed files using its normal download behavior.
4. The server exits after 15 minutes without an open page, active job, or file transfer.
5. The self-extractor removes its runtime directory after the server exits. Incomplete job data under `%TEMP%\TheHandPortable\jobs` is removed on the next launch.

The current executable is unsigned, so Windows SmartScreen may show an **Unknown publisher** warning.

## Build

From the repository root on Debian/Ubuntu:

```bash
sudo apt-get install p7zip-full gcc-mingw-w64-x86-64-posix ffmpeg curl
web/portable/build-windows-x64.sh
```

The script downloads pinned x64 component releases, verifies every SHA-256 digest, cross-compiles the launcher and branded SFX stub, and writes:

```text
web/portable/dist/The-Hand-Portable-r13-Windows-x64.exe
```
