"""Bounded HTTP -> HLS/stream probe -> actual video frame decode validation.

No device access. Dependencies: Python stdlib, curl, ffprobe, ffmpeg.
"""
import argparse
import concurrent.futures
import datetime
import ipaddress
import json
import pathlib
import re
import shutil
import subprocess
import tempfile
import time
import urllib.parse


def parse_playlist(text):
    entries, seen = [], set()
    info = "#EXTINF:-1,Unnamed"
    for line in text.lstrip("\ufeff").splitlines():
        line = line.strip()
        if line.startswith("#EXTINF:"):
            info = line
        elif line and not line.startswith("#"):
            if line in seen:
                continue
            seen.add(line)
            quoted = False
            comma = -1
            for i, char in enumerate(info):
                if char == '"':
                    quoted = not quoted
                if char == ',' and not quoted:
                    comma = i
                    break
            entries.append({"name": info[comma + 1:] if comma >= 0 else "Unnamed",
                            "info": info, "url": line})
    return entries


def run(command, timeout):
    try:
        process = subprocess.run(command, capture_output=True, timeout=timeout)
        return process.returncode, process.stdout, process.stderr.decode("utf-8", "replace")[-1600:]
    except subprocess.TimeoutExpired:
        return -1, b"", "process deadline exceeded"


def public_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username:
        return False
    try:
        return ipaddress.ip_address(parsed.hostname).is_global
    except ValueError:
        return parsed.hostname.lower() not in ("localhost",) and not parsed.hostname.endswith(".local")


def http_check(url, directory, curl):
    dest = pathlib.Path(directory) / "response"
    code, stdout, stderr = run([
        curl, "--ssl-revoke-best-effort", "--silent", "--show-error", "--location",
        "--proto", "=http,https", "--proto-redir", "=http,https", "--max-redirs", "5",
        "--connect-timeout", "5", "--max-time", "10", "--max-filesize", "2097152",
        "--range", "0-262143", "--output", str(dest),
        "--write-out", "%{http_code}\n%{url_effective}", url], 13)
    parts = stdout.decode("utf-8", "replace").splitlines()
    status = int(parts[0]) if parts and parts[0].isdigit() else 0
    final_url = parts[1] if len(parts) > 1 else url
    body = dest.read_bytes()[:2097152] if dest.exists() else b""
    if status not in (200, 206):
        return None, {"stage": "http", "reason": f"HTTP {status}" if status else stderr[:240] or "connection failed", "http_status": status}
    if not public_url(final_url):
        return None, {"stage": "http", "reason": "redirect to non-public/unsupported address", "http_status": status}
    # A server can stream endlessly or ignore Range. Keep a received media prefix for probing.
    if code != 0 and not body:
        return None, {"stage": "http", "reason": stderr[:240] or "empty response", "http_status": status}
    text = body.decode("utf-8-sig", "replace").lstrip()
    if text.lower().startswith(("<!doctype html", "<html", "<?xml")):
        return None, {"stage": "content", "reason": "HTML/XML instead of a video stream", "http_status": status}
    variants = []
    if text.startswith("#EXTM3U"):
        lines = text.splitlines()
        pending = None
        for line in lines:
            line = line.strip()
            if line.startswith("#EXT-X-STREAM-INF:"):
                match = re.search(r"(?:^|,)BANDWIDTH=(\d+)", line.split(":", 1)[1])
                pending = int(match.group(1)) if match else 0
            elif pending is not None and line and not line.startswith("#"):
                address = urllib.parse.urljoin(final_url, line)
                if public_url(address):
                    variants.append({"url": address, "bandwidth": pending})
                pending = None
        if "#EXT-X-" not in text:
            return None, {"stage": "content", "reason": "channel playlist, not an HLS media stream", "http_status": status}
    return {"http_status": status, "final_url": final_url,
            "variants": sorted(variants, key=lambda x: x["bandwidth"], reverse=True)}, None


def probe_decode(url, ffprobe, ffmpeg):
    protocol_args = ["-protocol_whitelist", "http,https,tcp,tls,crypto"]
    code, stdout, stderr = run([
        ffprobe, "-v", "error", "-rw_timeout", "7000000", *protocol_args,
        "-analyzeduration", "2500000", "-probesize", "2000000", "-show_entries",
        "stream=index,codec_type,codec_name,width,height", "-of", "json", url], 19)
    try:
        data = json.loads(stdout)
    except (ValueError, UnicodeDecodeError):
        data = {}
    videos = [s for s in data.get("streams", []) if s.get("codec_type") == "video"
              and s.get("width", 0) > 0 and s.get("height", 0) > 0]
    if code != 0 or not videos:
        return None, {"stage": "probe", "reason": "probe timeout" if code == -1 else
                      (stderr[:280] or "no readable video track/dimensions")}
    video = videos[0]
    code, stdout, stderr = run([
        ffmpeg, "-nostdin", "-hide_banner", "-v", "error", "-rw_timeout", "7000000",
        *protocol_args, "-analyzeduration", "2500000", "-probesize", "2000000",
        "-threads", "1", "-i", url, "-map", f"0:{video['index']}", "-an", "-sn",
        "-frames:v", "3", "-threads", "1", "-f", "framemd5", "-"], 20)
    frames = [line for line in stdout.decode("utf-8", "replace").splitlines()
              if line.strip() and not line.startswith("#")]
    if code != 0 or len(frames) < 3:
        return None, {"stage": "decode", "reason": "decode timeout" if code == -1 else
                      (stderr[:280] or f"only {len(frames)} decoded frames")}
    return {"width": video["width"], "height": video["height"],
            "video_codec": video.get("codec_name"), "decoded_frames": len(frames),
            "audio_codecs": sorted({s.get("codec_name", "unknown") for s in data.get("streams", [])
                                     if s.get("codec_type") == "audio"})}, None


def validate(entry, args):
    start = time.monotonic()
    result = {**entry, "valid": False, "checked_at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    if not public_url(entry["url"]):
        result.update(stage="input", reason="non-public or unsupported URL")
        return result
    with tempfile.TemporaryDirectory(prefix="simplelive-check-") as directory:
        response, error = http_check(entry["url"], directory, args.curl)
        if error:
            result.update(error)
        else:
            result.update(http_status=response["http_status"])
            variants = response["variants"]
            # Test the exact rendition rather than accepting an HLS master with an audio-only
            # or broken high-quality rendition. Limit attempts to keep the run bounded.
            candidates = variants[:3] if variants else [{"url": response["final_url"]}]
            attempts = []
            for position, candidate in enumerate(candidates):
                media, error = probe_decode(candidate["url"], args.ffprobe, args.ffmpeg)
                if media:
                    effective = entry["url"] if not variants or position == 0 else candidate["url"]
                    result.update(media, valid=True, stage="passed", reason="three video frames decoded",
                                  playback_url=effective, tested_url=candidate["url"],
                                  rendition_fallback=bool(variants and position > 0))
                    break
                attempts.append({"url": candidate["url"], **error})
            if not result["valid"]:
                result.update(attempts[-1] if attempts else {"stage": "content", "reason": "no media candidate"})
                result["url"] = entry["url"]
            result["failed_attempts"] = attempts
    result["elapsed_seconds"] = round(time.monotonic() - start, 2)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--curl", default=shutil.which("curl.exe") or shutil.which("curl"))
    parser.add_argument("--ffprobe", default=shutil.which("ffprobe"))
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    args = parser.parse_args()
    if not all((args.curl, args.ffprobe, args.ffmpeg)):
        parser.error("curl, ffprobe and ffmpeg are required")
    args.output.mkdir(parents=True, exist_ok=True)
    entries = parse_playlist(args.input.read_text(encoding="utf-8-sig"))
    start = time.monotonic()
    results = {}
    valid_count = 0
    print(f"Checking {len(entries)} unique streams with {args.workers} workers", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        jobs = {pool.submit(validate, entry, args): i for i, entry in enumerate(entries)}
        for done, future in enumerate(concurrent.futures.as_completed(jobs), 1):
            index = jobs[future]
            try:
                result = future.result()
            except Exception as error:
                result = {**entries[index], "valid": False, "stage": "internal", "reason": str(error)}
            results[index] = result
            valid_count += int(result["valid"])
            print(f"[{done}/{len(entries)}] {'PASS' if result['valid'] else 'FAIL'} {result['name']} | {result['stage']} | valid={valid_count}", flush=True)
            (args.output / "progress.json").write_text(json.dumps(
                {"completed": done, "total": len(entries), "valid": valid_count}, ensure_ascii=False), encoding="utf-8")
    ordered = [results[i] for i in range(len(entries))]
    valid = [item for item in ordered if item["valid"]]
    rejected = [item for item in ordered if not item["valid"]]
    playlist = ["#EXTM3U", "# Validated by HTTP, ffprobe and 3-frame decode; not a TV-device compatibility guarantee."]
    text_list = []
    for item in valid:
        info = item["info"]
        # Preserve source metadata; record measured dimensions separately in JSON.
        playlist.extend([info, item["playback_url"]])
        text_list.append(f"{item['name']},{item['playback_url']}")
    (args.output / "valid.m3u").write_text("\n".join(playlist) + "\n", encoding="utf-8")
    (args.output / "valid.txt").write_text("\n".join(text_list) + "\n", encoding="utf-8")
    (args.output / "results.json").write_text(json.dumps(ordered, ensure_ascii=False, indent=2), encoding="utf-8")
    reasons = {}
    for item in rejected:
        reasons[item["stage"]] = reasons.get(item["stage"], 0) + 1
    summary = {"input": str(args.input), "tested": len(entries), "passed": len(valid), "rejected": len(rejected),
               "failure_stages": reasons, "elapsed_seconds": round(time.monotonic() - start, 1),
               "rendition_fallbacks": sum(item.get("rendition_fallback", False) for item in valid),
               "method": "HTTP status/content, ffprobe video metadata, actual decode of three video frames",
               "limits": "Local computer network only; no TV used; no long-duration stability or audio sync check. URLs may expire."}
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.output / "rejected.txt").write_text("\n\n".join(
        f"{item['name']}\n{item['url']}\n{item['stage']}: {item['reason']}" for item in rejected), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
