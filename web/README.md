# The Hand — web app

A private, server-backed web interface for The Hand. Browser JavaScript cannot run yt-dlp or ffmpeg safely by itself, so the server performs extraction and conversion and exposes only bounded job APIs.

## Features

- Public HTTP/HTTPS URL validation with private-network blocking
- Single-item extraction (`--no-playlist`)
- Deduplicated video quality choices and MP3 audio extraction
- Four concurrent fragments for DASH/HLS media
- Live byte, speed, ETA, and percentage progress
- Queueing, cancellation, time and file-size limits
- Temporary downloads removed after the retention window
- Responsive installable web UI

## Requirements

- Node.js 20+
- Python 3
- ffmpeg/ffprobe
- The bundled `library/src/main/res/raw/ytdlp`, or `YTDLP_PATH` pointing to another yt-dlp executable

## Local development

From the repository root:

```bash
node --test web/test/*.test.mjs
node web/server/server.mjs
```

Open <http://127.0.0.1:8787>.

## Container

From the repository root:

```bash
docker compose -f web/compose.yaml up --build
```

The container runs without root capabilities, uses a read-only root filesystem, and binds the host port to `127.0.0.1`. Downloads live in the `the-hand-jobs` volume until they expire.

## Configuration

| Variable | Default | Purpose |
| --- | ---: | --- |
| `HOST` | `127.0.0.1` | Server bind address |
| `PORT` | `8787` | HTTP port |
| `DATA_DIR` | `/tmp/the-hand-web` | Temporary job storage |
| `YTDLP_PATH` | bundled binary | yt-dlp executable or zipapp |
| `MAX_ACTIVE_JOBS` | `2` | Concurrent downloads |
| `MAX_DOWNLOAD_BYTES` | `4294967296` | Per-job size ceiling |
| `JOB_RETENTION_MS` | `3600000` | Completed-file retention |

## Security boundary

This is intended for private use. It rejects local/private URL targets, custom ports, credentials in URLs, oversized requests, and arbitrary format arguments. It also uses a small request-rate limit and spawns yt-dlp without a shell. Those controls are not a substitute for authentication on a public deployment.
