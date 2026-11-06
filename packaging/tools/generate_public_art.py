"""Render the public blue-and-gold watch art from original geometric shapes."""
from pathlib import Path
from math import cos, pi, sin, sqrt
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "src/app/src/main/res/drawable-nodpi"
RES.mkdir(parents=True, exist_ok=True)

GOLD = (239, 192, 119)
PALE = (181, 227, 252)
BLUE = (64, 141, 208)

def star(draw, x, y, r, fill):
    draw.polygon([(x, y-r),(x+r*.28,y-r*.26),(x+r,y),
                  (x+r*.28,y+r*.26),(x,y+r),(x-r*.28,y+r*.26),
                  (x-r,y),(x-r*.28,y-r*.26)],fill=fill)

def tail(size, accent, ring=True):
    up = 3
    u = size * up
    im = Image.new("RGBA", (u,u))
    d = ImageDraw.Draw(im)
    def scaled(points):
        return [(round(x*u),round(y*u)) for x,y in points]
    if ring:
        d.ellipse((.067*u,.067*u,.933*u,.933*u),fill=(11,33,66,255),outline=(240,193,117,230),width=max(2,round(.011*u)))
        d.ellipse((.112*u,.112*u,.888*u,.888*u),outline=(81,160,220,125),width=max(2,round(.005*u)))
    d.polygon(scaled([(.13,.31),(.25,.31),(.37,.36),(.47,.49),(.50,.53),
                      (.53,.49),(.63,.36),(.75,.31),(.87,.31),(.83,.44),
                      (.74,.54),(.63,.60),(.56,.63),(.55,.77),(.50,.82),
                      (.45,.77),(.44,.63),(.37,.60),(.26,.54),(.17,.44)]),
              fill=BLUE+(255,))
    d.line(scaled([(.17,.33),(.28,.34),(.38,.40),(.50,.54),(.62,.40),(.72,.34),(.83,.33)]),
           fill=PALE+(255,),width=max(2,round(.013*u)),joint="curve")
    star(d,.50*u,.23*u,.065*u,accent+(255,))
    return im.resize((size,size),Image.Resampling.LANCZOS)

def water(size, panel):
    base=Image.new("RGB",(size,size))
    pixels=base.load()
    center=.22 if panel else .77
    for y in range(size):
        for x in range(size):
            fx,fy=x/size,y/size
            glow=max(0,1-sqrt((fx-center)**2+(fy-.48)**2)*1.28)
            tide=(sin((fy*2.1+fx*.44)*pi*2)+1)/2
            pixels[x,y]=(int(6+9*glow),int(21+29*glow+3*tide),int(44+39*glow+7*tide))
    layer=Image.new("RGBA",base.size)
    d=ImageDraw.Draw(layer)
    for i in range(8):
        y0=(.21+i*.084)*size
        d.line([(x,y0+size*(.018+i*.002)*sin(x/size*2*pi+i*.54)) for x in range(0,size+1,4)],
               fill=(100,186,238,30+i*2),width=2)
    for angle in range(0,360,6):
        theta=angle*pi/180
        l=.012 if angle%30==0 else .005
        d.line([((.5+(.475-l)*cos(theta))*size,(.5+(.475-l)*sin(theta))*size),
                ((.5+.475*cos(theta))*size,(.5+.475*sin(theta))*size)],
               fill=(230,184,109,115 if angle%30==0 else 43),width=2)
    for x,y,r in [(.13,.36,.025),(.21,.75,.014),(.75,.22,.012),(.85,.66,.018),(.62,.78,.009)]:
        d.ellipse(((x-r)*size,(y-r)*size,(x+r)*size,(y+r)*size),outline=(148,215,250,48),width=2)
    return Image.alpha_composite(base.convert("RGBA"),layer).convert("RGB")

for stem, accent in {
    "greet": GOLD, "listen": (137,216,247), "drawer": (156,209,241),
    "confirm": GOLD, "chat": PALE, "log": (114,197,241),
}.items():
    tail(320,accent).save(RES / f"whale_{stem}.webp",lossless=True)
tail(512,GOLD).save(RES / "whale_portrait.png")
tail(192,GOLD).save(ROOT / "docs/media/icon.png")
for name, panel in [("chat",False),("panel",True)]:
    water(640,panel).save(RES / f"whale_tide_{name}.webp",quality=89)
