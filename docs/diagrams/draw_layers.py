"""手繪分層架構圖：四層橫帶由上往下堆疊，層與層之間標示溝通方式。"""
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = 'C:/Windows/Fonts/msjh.ttc'
BOLD = 'C:/Windows/Fonts/msjhbd.ttc'

W = 1300
BAND_H = 150
GAP = 64
PAD = 24
f_title = ImageFont.truetype(BOLD, 24)
f_box = ImageFont.truetype(FONT, 21)
f_box_small = ImageFont.truetype(FONT, 17)
f_label = ImageFont.truetype(FONT, 19)

layers = [
    ('展示層　瀏覽器 SPA（TypeScript + Three.js）', '#EAF2FB', [
        ('WorldView', '3D 場景、位置內插'),
        ('UI 面板', '指令列、HUD、背包、商店、任務、技能、隊伍'),
        ('GameConnection／api', 'Protobuf 編解碼、REST'),
    ]),
    ('閘道層　Spring Boot（Web 容器執行緒）', '#EEF7EE', [
        ('AuthController', 'REST /api/auth、JWT'),
        ('LoginThrottle', '登入限流、帳號鎖定'),
        ('GameWebSocketHandler', 'WebSocket /ws、解碼'),
        ('ConnectionLimits', '訊息頻率限制'),
    ]),
    ('遊戲核心層　純 Java，不依賴 Spring（區域執行緒）', '#FFF6E5', [
        ('World／Zone', '區域生命週期、tick 迴圈'),
        ('Combat／NpcBrain', '戰鬥、NPC 行為'),
        ('Commands', '文字指令'),
        ('背包／任務／技能', 'Inventory、QuestLog、SkillBook'),
        ('PartyService', '跨區共用的組隊'),
    ]),
    ('持久層　Spring Data JPA（存檔執行緒）', '#F3EEF8', [
        ('CharacterPersistence', '非同步、單一交易存檔'),
        ('Repositories', '帳號、角色與子表'),
        ('Flyway', '資料庫遷移 V1～V6'),
    ]),
]
links = ['HTTPS／JSON（登入）　WebSocket／Protobuf（遊戲）',
         'ZoneEvent 佇列（每個區域一個）',
         'CharacterSnapshot（不阻塞 tick）']

DB_H = 90
H = PAD * 2 + len(layers) * BAND_H + (len(layers) - 1) * GAP + GAP + DB_H
img = Image.new('RGB', (W, H), 'white')
d = ImageDraw.Draw(img)


def centered(text, font, cx, y, fill='#222222'):
    w = d.textlength(text, font=font)
    d.text((cx - w / 2, y), text, font=font, fill=fill)


def arrow(x, y1, y2, label):
    d.line([(x, y1), (x, y2)], fill='#333333', width=3)
    d.polygon([(x - 9, y2 - 14), (x + 9, y2 - 14), (x, y2)], fill='#333333')
    d.text((x + 18, (y1 + y2) / 2 - 13), label, font=f_label, fill='#333333')


y = PAD
for i, (title, color, boxes) in enumerate(layers):
    d.rounded_rectangle([PAD, y, W - PAD, y + BAND_H], radius=10, fill=color, outline='#666666', width=2)
    d.text((PAD + 16, y + 10), title, font=f_title, fill='#222222')
    n = len(boxes)
    inner_w = W - 2 * PAD - 32
    gap = 14
    bw = (inner_w - gap * (n - 1)) / n
    by = y + 52
    for j, (name, desc) in enumerate(boxes):
        bx = PAD + 16 + j * (bw + gap)
        d.rounded_rectangle([bx, by, bx + bw, by + 82], radius=6, fill='white', outline='#555555', width=2)
        font = f_box if d.textlength(name, font=f_box) < bw - 12 else f_box_small
        centered(name, font, bx + bw / 2, by + 10)
        # 說明文字太長時換成兩行
        if d.textlength(desc, font=f_box_small) < bw - 12:
            centered(desc, f_box_small, bx + bw / 2, by + 46, '#555555')
        else:
            half = len(desc) // 2
            cut = desc.find('、', half - 3)
            cut = cut + 1 if cut != -1 else half
            centered(desc[:cut], f_box_small, bx + bw / 2, by + 40, '#555555')
            centered(desc[cut:], f_box_small, bx + bw / 2, by + 60, '#555555')
    if i < len(links):
        arrow(W / 2, y + BAND_H + 4, y + BAND_H + GAP - 4, links[i])
    y += BAND_H + GAP

# 資料庫
arrow(W / 2, y - GAP + 4, y - 4, 'JDBC')
cx, top = W / 2, y
d.ellipse([cx - 120, top, cx + 120, top + 26], fill='white', outline='#555555', width=2)
d.rectangle([cx - 120, top + 13, cx + 120, top + DB_H - 13], fill='white')
d.line([(cx - 120, top + 13), (cx - 120, top + DB_H - 13)], fill='#555555', width=2)
d.line([(cx + 120, top + 13), (cx + 120, top + DB_H - 13)], fill='#555555', width=2)
d.arc([cx - 120, top + DB_H - 26, cx + 120, top + DB_H], 0, 180, fill='#555555', width=2)
centered('PostgreSQL', f_box, cx, top + 38)

out = os.path.join(HERE, 'layers.png')
img.save(out, dpi=(150, 150))
print(out, img.size)
