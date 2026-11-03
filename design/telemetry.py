import math
from lib import *
from home import card

def arc_path(cx,cy,r,a0,a1):
    """ângulos em graus (matemático, y para cima); sentido horário na tela de a0 até a1 (a0>a1)."""
    p=lambda a:(cx+r*math.cos(math.radians(a)),cy-r*math.sin(math.radians(a)))
    x0,y0=p(a0); x1,y1=p(a1); large=1 if (a0-a1)>180 else 0
    return f"M{x0:.2f} {y0:.2f} A{r} {r} 0 {large} 1 {x1:.2f} {y1:.2f}"

def stat(b,p,name,x,y,w,h,icon,label,value,unit,sub=None,state="ok",iconcolor="primary"):
    c=card(b,p,name,x,y,w,h,"ok")
    tile=b.frame("ic-tile",12,(h-40)//2,40,40,parent=c,fill="track",radius=12)
    b.icon(tile,icon,8,8,24,iconcolor)
    b.text(c,label,62,10,size=12,color="text2",w=w-70)
    b.text(c,value+(" "+unit if unit else ""),62,28,size=20,weight="Bold",w=w-70,color="text" if value!="--" else "text2")
    if sub: b.text(c,sub,62,h-22,size=11,color="text2",w=w-70)
    return c

def telemetria(b,x,y,name,speed=0,motor2=True,bars=None):
    f=b.frame(name,x,y,W,H,fill="bg",clip=True)
    # topo
    back=b.frame("voltar",8,16,48,48,parent=f); b.refs[name+":voltar"]=back
    b.icon(back,"back",12,12,24,"text")
    b.text(f,"Telemetria",0,20,size=20,weight="Bold",w=W,align="center",h=40)
    # dispositivo
    d=card(b,f,"cartao-dispositivo",16,68,328,60)
    b.icon(d,"scooter",14,17,26,"primary")
    b.text(d,"iX7 Pro",56,8,size=16,weight="Bold",w=180)
    b.frame("dot",56,38,8,8,parent=d,fill="success",radius=4)
    b.text(d,"Conectado",70,30,size=13,color="text2",w=160,h=24)
    bt=b.frame("bt",264,12,36,36,parent=d,fill="track",radius=18); b.icon(bt,"bluetooth",6,6,24,"primary")
    b.icon(d,"chev",300,18,24,"text2")
    # velocímetro
    cx,cy,r=180,262,106
    a1=b.uid("arc"); a2=b.uid("arc")
    b.add("vector.create",{"path":arc_path(cx,cy,r,210,-30),"x":0,"y":0,"parent":f,"name":"arco-trilho","select":False},a1)
    b.add("node.set",{"id":{"$ref":a1},"fill":"none","stroke":{"$var":"track"},"strokeWeight":14,"dashes":[5,4],"strokeCap":"none"})
    frac=min(speed/60,1)
    if frac>0:
        b.add("vector.create",{"path":arc_path(cx,cy,r,210,210-240*frac),"x":0,"y":0,"parent":f,"name":"arco-velocidade","select":False},a2)
        b.add("node.set",{"id":{"$ref":a2},"fill":"none","stroke":{"$var":"primary"},"strokeWeight":14,"dashes":[5,4],"strokeCap":"none",
                          "effects":[{"type":"dropShadow","color":"#00E5FF66","offset":[0,0],"radius":14}]})
    b.text(f,str(speed),cx-70,cy-62,size=56,weight="Bold",w=84,align="right",h=70)
    b.text(f,"km/h",cx+18,cy-40,size=18,weight="SemiBold",color="text2",w=60,h=30)
    b.text(f,"Velocidade atual",cx-80,cy+6,size=13,color="text2",w=160,align="center")
    b.text(f,"0",cx-r*0.87-14,cy+r*0.5+2,size=13,color="text2",w=30,align="center")
    b.text(f,"60",cx+r*0.87-14,cy+r*0.5+2,size=13,color="text2",w=30,align="center")
    # bateria
    pill=b.frame("pilula-bateria",90,344,180,38,parent=f,fill="card",stroke="primary",radius=19)
    b.icon(pill,"battery",12,7,24,"success")
    b.text(pill,"82%",44,0,size=16,weight="Bold",w=50,h=38)
    b.frame("sep",100,10,1,18,parent=pill,fill="stroke")
    b.text(pill,"Bateria",112,0,size=13,color="text2",w=60,h=38)
    # consumo em tempo real
    amp,volt=(8.4,54.9) if speed>0 else (0.0,55.4)
    watt=round(amp*volt)
    c=card(b,f,"cartao-consumo",16,388,328,92)
    b.text(c,"Consumo em tempo real",14,10,size=12,color="text2",w=200)
    b.text(c,f"{watt} W",14,28,size=28,weight="Bold",w=140,h=36)
    b.text(c,(f"{watt/speed:.1f} Wh/km".replace(".",",") if speed>0 else "Parado · sem consumo"),14,66,size=12,color="text2",w=150)
    bars=bars or ([0]*24 if speed==0 else [30,34,40,52,60,58,48,44,50,62,70,66,58,52,48,56,64,72,68,60,54,50,58,62])
    for i,v in enumerate(bars):
        hh=max(3,v*0.6)
        b.frame("barra",168+i*6.4,70-hh+0,4,hh,parent=c,fill="primary" if speed>0 else "track",radius=2)
    b.text(c,"últimos 30 s",168,74,size=10,color="text2",w=150)
    # corrente / tensão
    stat(b,f,"cartao-corrente",16,490,160,64,"pulse","Corrente",f"{amp:.1f}".replace(".",","),"A")
    stat(b,f,"cartao-tensao",184,490,160,64,"bolt","Tensão",f"{volt:.1f}".replace(".",","),"V")
    # temperaturas
    stat(b,f,"cartao-temp-motor1",16,562,160,76,"thermo","Temp. motor 1","22" if speed==0 else "41","°C")
    stat(b,f,"cartao-temp-motor2",184,562,160,76,"thermo","Temp. motor 2","--" if not motor2 else ("22" if speed==0 else "39"),"°C" if motor2 else "",sub=None if motor2 else "Sem leitura",iconcolor="primary" if motor2 else "text2")
    # odômetros
    o=card(b,f,"cartao-odometro",16,648,328,72)
    for j,(ic,lab,val) in enumerate([("route","Odômetro atual","0,00 km"),("speed","Odômetro total","1.248 km")]):
        ox=14+j*160
        t=b.frame("ic-tile",ox,16,40,40,parent=o,fill="track",radius=12); b.icon(t,ic,8,8,24,"primary")
        b.text(o,lab,ox+50,14,size=11,color="text2",w=100)
        b.text(o,val,ox+50,32,size=17,weight="Bold",w=100)
    b.frame("div",164,14,1,44,parent=o,fill="stroke")
    return f
