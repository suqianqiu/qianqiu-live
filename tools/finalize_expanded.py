"""Merge computer-validation results, localize labels and select one route per channel."""
import collections
import json
import pathlib
import re
import sys
import urllib.parse
from validate_streams import parse_playlist


def norm(value):
    value = value.translate(str.maketrans('臺灣視聞鳳資訊衛綜藝無線國際東廣電體育華麗財經門澳龍',
                                         '台湾视闻凤资讯卫综艺无线国际东广电体育华丽财经门澳龙'))
    value = re.sub(r'\([^)]*\)|（[^）]*）|\[[^]]*\]|「[^」]*」', '', value)
    return re.sub(r'\s|[-_·]|(?:FHD|HD|SD)|高清|超清|标清', '', value, flags=re.I).lower()


def has_chinese(text):
    return any('\u4e00' <= c <= '\u9fff' for c in text)


def main():
    directory = pathlib.Path(sys.argv[1])
    db = json.loads((directory / 'sources/channel-db-compressed.json').read_text(encoding='utf-8'))
    by_id = {x['id'].lower(): x for x in db}
    aliases = {}
    for channel in db:
        if channel['country'] not in ('CN', 'HK', 'MO', 'TW'):
            continue
        for alias in [channel['name'], *channel['alt_names'], channel['id'].split('.')[0]]:
            aliases.setdefault(norm(alias), channel)
    old = parse_playlist((directory / 'previous-52-zh.m3u').read_text(encoding='utf-8'))
    labels = {}
    for entry in old:
        identifier = re.search(r'tvg-id="([^"]+)', entry['info'])
        if identifier:
            labels[identifier.group(1).split('@')[0].lower()] = entry['name']
    labels.update({k.lower(): v for k, v in {
        'PetClubTV.hk': '宠物俱乐部电视台', 'DiscoveryAsiaTaiwan.tw': '探索亚洲台湾台',
        'PTS3.tw': '公视第三台', 'TaiwanIndigenousTV.tw': '原住民族电视台',
        'IndigenousTV.tw': '原住民族电视台', 'TVBSAsia.tw': 'TVBS亚洲台',
        'AXNAsia.sg': 'AXN台湾台', 'HBOAsia.sg': 'HBO台湾台', 'SJTV.tw': '生命电视台',
        'CanalMacau.mo': '澳视葡文', 'LotusTV.mo': '澳门莲花卫视',
        'TDMOuMun.mo': '澳视澳门', 'TDMEntertainment.mo': '澳门综艺',
        'TDMInformation.mo': '澳门资讯', 'TDMSports.mo': '澳门体育',
        'TDMMacauSatellite.mo': '澳门卫星', 'BeautifulLifeTV.tw': '人间卫视',
        'DaliTV.tw': '大立电视台', 'EBCForeignMovie.tw': '东森洋片台',
        'SupremeMasterTV.tw': '无上师电视台', 'WXTV.tw': '唯心电视台',
        'CCTV4.cn': '央视四套·中文国际', 'CCTV5.cn': '央视五套·体育',
        'CCTV5+.cn': '央视体育赛事', 'CCTV16.cn': '央视十六套·奥林匹克'
    }.items()})
    manual = {
        '翡翠台': ('Jade.hk', 'HK'), '明珠台': ('Pearl.hk', 'HK'),
        '凤凰中文': ('PhoenixChineseChannel.hk', 'HK'), '凤凰资讯': ('PhoenixInfoNewsChannel.hk', 'HK'),
        '凤凰香港': ('PhoenixHongKongChannel.hk', 'HK'), '无线新闻': ('TVBNewsChannel.hk', 'HK'),
        'viutv': ('ViuTV.hk', 'HK'), 'j2': ('TVBPlus.hk', 'HK'),
        '三立新闻': ('SETNews.tw', 'TW'), '三立台湾': ('SETTaiwan.tw', 'TW'),
        'tvbs亚洲': ('TVBSAsia.tw', 'TW'), '纬来体育': ('VideolandSports.tw', 'TW'),
        '纬来育乐': ('VideolandMaxTV.tw', 'TW'), '东森综合': ('EBCVariety.tw', 'TW'),
        '东森超视': ('EBCSuperTV.tw', 'TW'), '东森电影': ('EBCMovie.tw', 'TW'),
        '靖天资讯': ('JingTianInfo.tw', 'TW')
    }
    manual = {norm(k): v for k, v in manual.items()}
    metadata = {}
    for filename in ('candidates.json', 'extra-selected.json', 'focused-new.json', 'macau2.json'):
        metadata.update({x['url']: x for x in json.loads((directory / filename).read_text(encoding='utf-8'))})
    results = []
    for folder in ('pass1', 'extra-pass1', 'focused-new', 'macau2'):
        results.extend(json.loads((directory / folder / 'results.json').read_text(encoding='utf-8')))
    # Optional targeted rechecks supersede only the failed entries that were retried.
    retry_file = directory / 'retry/results.json'
    retry = {x['url']: x for x in json.loads(retry_file.read_text(encoding='utf-8'))} if retry_file.exists() else {}
    results = [retry.get(x['url'], x) if not x['valid'] else x for x in results]
    regions = {'CN': '大陆', 'HK': '香港', 'MO': '澳门', 'TW': '台湾'}
    selected = {}
    excluded = []
    all_valid = []
    for result in results:
        if not result['valid']:
            continue
        if re.search(r'[\s"<>\\^`{|}]|%(?![0-9a-fA-F]{2})', result['playback_url']):
            excluded.append({'name': result['name'], 'reason': 'URL syntax rejected by Android playlist parser', 'url': result['url']})
            continue
        meta = metadata[result['url']]
        match = re.search(r'tvg-id="([^"]*)', result['info'])
        identifier = match.group(1).split('@')[0].lower() if match else ''
        raw_name = result['name']
        clean_name = re.sub(r'\s*\([^)]*\)|\s*\[[^]]*\]|「[^」]*」', '', raw_name).strip()
        channel = by_id.get(identifier) or aliases.get(norm(clean_name))
        manual_entry = manual.get(norm(clean_name))
        region = {'iptv-cn': 'CN', 'iptv-hk': 'HK', 'iptv-mo': 'MO', 'iptv-tw': 'TW',
                  'previous-bundle': 'CN', 'fmm-ipv6': 'CN'}.get(meta['source'])
        if not region and channel:
            region = channel['country']
        if manual_entry:
            identifier, region = manual_entry
            identifier = identifier.lower()
            channel = by_id.get(identifier) or channel
        group_match = re.search(r'group-title="([^"]*)', result['info'])
        group = group_match.group(1) if group_match else ''
        if clean_name in ('七龙珠', '中华小当家', '蜡笔小新', '猫和老鼠', '火影忍者', '海绵宝宝', '漫画解说'):
            excluded.append({'name': raw_name, 'reason': 'programme loop rather than a TV channel', 'url': result['url']})
            continue
        if not region and meta['source'] == 'guovin':
            if '港·澳·台' in group:
                excluded.append({'name': raw_name, 'reason': 'region not identified', 'url': result['url']})
                continue
            region = 'CN'
        if not region and meta['source'] == 'free-tv':
            region = {'China': 'CN', 'Hong Kong': 'HK', 'Macau': 'MO', 'Taiwan': 'TW'}.get(group)
            if 'cgtn' in clean_name.lower():
                region = 'CN'
        if region not in regions:
            excluded.append({'name': raw_name, 'reason': 'outside requested regions', 'url': result['url']})
            continue
        name = labels.get(identifier, clean_name)
        if not has_chinese(name) and channel:
            name = next((n for n in channel['alt_names'] if has_chinese(n)), name)
        if not has_chinese(name):
            name = {'J2': '无线第二台', 'ViuTV': '香港ViuTV', 'CGTN': '中国国际电视台英语频道',
                    'CGTN Documentary': '中国国际电视台纪录频道', 'CGTN Arabic': '中国国际电视台阿拉伯语频道',
                    'CGTN Spanish': '中国国际电视台西班牙语频道', 'CGTN French': '中国国际电视台法语频道',
                    'CGTN Russian': '中国国际电视台俄语频道'}.get(name, regions[region] + '·' + name)
        cctv_pattern = r'^CCTV[ -]?(\d{1,2})(\+)?(?!\d|K)'
        cctv = re.match(cctv_pattern, clean_name, re.I) or re.match(cctv_pattern, identifier, re.I)
        if cctv:
            key = 'cctv' + cctv.group(1) + (cctv.group(2) or '')
            if not has_chinese(clean_name) or key + '.cn' in labels:
                name = next((v for k, v in labels.items() if k == key + '.cn'), '央视' + cctv.group(1) + (cctv.group(2) or '') + '套')
        else:
            key = norm(channel['id'] if channel else identifier or name)
        if manual_entry:
            key = norm(identifier)
        # Prefer H.264 at 720p/1080p for the Android 5 TV over an expensive 4K/HEVC route.
        width = result['width']
        host = urllib.parse.urlsplit(result['playback_url']).hostname or ''
        score = (30 if result['video_codec'] == 'h264' else 0) + (20 if 1280 <= width <= 1920 else 10 if width < 1280 else 0)
        score += 3 if ':' not in host else 0
        score += 2 if result['playback_url'].startswith('https://') else 0
        item = {**result, 'display_name': name, 'region': region, 'channel_key': key, 'score': score, 'source': meta['source']}
        all_valid.append(item)
        identity = (region, key)
        if identity not in selected or score > selected[identity]['score']:
            selected[identity] = item
    def ordering(item):
        if item['region'] != 'CN':
            return (2, ['HK', 'MO', 'TW'].index(item['region']), item['display_name'])
        c = re.match(r'cctv(\d+)(\+)?$', item['channel_key'])
        if c:
            return (0, int(c.group(1)) * 2 + bool(c.group(2)), '')
        return (1 if '卫视' in item['display_name'] else 3, 0, item['display_name'])
    # Some upstreams use different IDs for the same localized channel name.
    by_name = {}
    for item in selected.values():
        identity = (item['region'], norm(item['display_name']))
        if identity not in by_name or item['score'] > by_name[identity]['score']:
            by_name[identity] = item
    channels = sorted(by_name.values(), key=ordering)
    assert channels, 'No channels passed; bundle will not be updated'
    lines = ['#EXTM3U', '# Computer validated 2026-10-22: HTTP, video track and 3 decoded frames.']
    txt = []
    for item in channels:
        group = regions[item['region']]
        display = item['display_name'] if item['region'] == 'CN' else group + '·' + item['display_name']
        lines.extend([f'#EXTINF:-1 group-title="{group}",{display}', item['playback_url']])
        txt.append(display.replace(',', '，') + ',' + item['playback_url'])
    playlist = '\n'.join(lines) + '\n'
    assert len(parse_playlist(playlist)) == len(channels)
    (directory / 'valid-expanded.m3u').write_text(playlist, encoding='utf-8')
    (directory / 'valid-expanded.txt').write_text('\n'.join(txt) + '\n', encoding='utf-8')
    summary = {'date': '2026-10-22', 'candidate_urls': len(results), 'passed_urls': sum(x['valid'] for x in results),
               'channels': len(channels), 'regions': {k: sum(x['region'] == k for x in channels) for k in regions},
               'retry_count': len(retry), 'excluded_decodable_entries': len(excluded),
               'method': 'Computer only: HTTP GET, ffprobe video dimensions, software decode of 3 frames.',
               'limits': 'No content-identity, audio, long-duration or TV decoding guarantee.'}
    for filename, data in [('summary.json', summary), ('selected-channels.json', channels),
                           ('results-expanded.json', results), ('all-valid-routes.json', all_valid), ('excluded.json', excluded)]:
        (directory / filename).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    report = ['公开电视源扩充报告（2026-10-22）', f'候选地址：{len(results)}；通过地址：{summary["passed_urls"]}',
              f'合并同台线路后：{len(channels)}个频道', '地区数量：' + '；'.join(regions[k] + str(v) for k, v in summary['regions'].items()),
              '电脑端验证：HTTP可访问、有效视频轨道、至少3帧实际解码。',
              '只证明检查当时能解码，未验证节目内容身份、音画同步或长期稳定性。', '', '已添加频道：']
    report += [regions[x['region']] + ' | ' + x['display_name'] + f' | {x["width"]}x{x["height"]} | ' + x['source'] for x in channels]
    report += ['', '可解码但未收录的条目（格式不兼容或非电视频道）：']
    report += [x['name'] + ' | ' + x['reason'] for x in excluded]
    report += ['', '未通过地址及原因：']
    report += [x['name'] + ' | ' + x['stage'] + ' | ' + x.get('reason', '').replace('\n', ' ')[:180] for x in results if not x['valid']]
    (directory / 'report.txt').write_text('\n'.join(report) + '\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main()
