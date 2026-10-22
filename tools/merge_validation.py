"""Merge a first scan and failed-only retry, preserving original channel ordering."""
import collections
import json
import pathlib
import sys

from validate_streams import parse_playlist


def main():
    directory = pathlib.Path(sys.argv[1])
    first = json.loads((directory / "results.json").read_text(encoding="utf-8"))
    retry = json.loads((directory / "retry/results.json").read_text(encoding="utf-8"))
    retried = {item["url"]: item for item in retry}
    if set(retried) != {item["url"] for item in first if not item["valid"]}:
        raise ValueError("Retry set does not exactly match the failed first-pass set")
    merged = []
    recovered = 0
    for item in first:
        later = retried.get(item["url"])
        selected = dict(later or item)
        selected["validation_history"] = [
            {"pass": 1, "valid": item["valid"], "stage": item["stage"],
             "reason": item["reason"], "checked_at": item.get("checked_at")}
        ]
        if later:
            selected["validation_history"].append(
                {"pass": 2, "valid": later["valid"], "stage": later["stage"],
                 "reason": later["reason"], "checked_at": later.get("checked_at")})
            recovered += int(later["valid"])
        merged.append(selected)
    valid = [item for item in merged if item["valid"]]
    rejected = [item for item in merged if not item["valid"]]
    if not valid:
        raise ValueError("No valid streams; refusing to produce a replacement bundle")
    m3u = ["#EXTM3U", "# Computer-validated 2026-10-21: HTTP + ffprobe + 3 decoded video frames.",
           "# Failed first-pass addresses were retried. No TV device was used for this scan."]
    for item in valid:
        m3u.extend([item["info"], item["playback_url"]])
    valid_text = "\n".join(m3u) + "\n"
    if len(parse_playlist(valid_text)) != len(valid):
        raise ValueError("Output playlist count mismatch")
    (directory / "valid-final.m3u").write_text(valid_text, encoding="utf-8")
    (directory / "valid-final.txt").write_text("\n".join(
        item["name"].replace(",", "，") + "," + item["playback_url"] for item in valid) + "\n", encoding="utf-8")
    (directory / "results-final.json").write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8")
    reasons = dict(collections.Counter(item["stage"] for item in rejected))
    summary = {
        "date": "2026-10-21", "original": len(first), "first_pass_valid": sum(x["valid"] for x in first),
        "retried": len(retry), "recovered_in_retry": recovered, "valid": len(valid), "removed": len(rejected),
        "failure_stages": reasons, "codec_counts": dict(collections.Counter(x["video_codec"] for x in valid)),
        "lower_rendition_replacements": sum(x.get("rendition_fallback", False) for x in valid),
        "method": "HTTP GET/range + ffprobe video dimensions + decode of 3 video frames; failed addresses retried once",
        "tested_on_tv": False,
        "limits": "Computer network at check time only. Does not verify channel identity, rights, long-term uptime, audio sync or TV compatibility. Lower rendition URLs may expire."
    }
    (directory / "summary-final.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    report = ["电视播放列表网络筛选报告（2026-10-21）", "",
              f"原始地址：{len(first)}", f"首轮通过：{summary['first_pass_valid']}",
              f"失败地址复测：{len(retry)}；复测找回：{recovered}",
              f"最终保留：{len(valid)}；本轮剔除：{len(rejected)}", "",
              "通过标准：HTTP可访问，读到有效视频轨道和分辨率，实际解码至少3帧。",
              "全程仅在电脑上访问网络地址，没有操作电视。",
              "失败不代表永久失效：可能是网络、地区、超时、源站限流或媒体轨道问题。",
              "可解码不代表频道名称正确；没有进行节目内容识别或长期稳定性测试。",
              "多清晰度入口必要时替换为可解码分流；这种分流地址可能有时效。", "",
              "有效频道（实际探测分辨率）："]
    for item in valid:
        report.append(f"{item['name']} | {item['video_codec']} | {item['width']}x{item['height']}")
    report.extend(["", "未通过地址："])
    for item in rejected:
        report.append(f"{item['name']} | {item['stage']} | {item['reason'].replace(chr(10), ' ')[:240]}")
    (directory / "report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
