"""S45: mockups A-E (viagem sem percurso com mapa desfocado, ícone GPS, Parar desabilitado, consumo em escala absoluta 0–750 W).
Só tokens existentes de lib.py. fs=1 (normal) ou 2 (fonte 2,0, mesma escala de s43.S)."""
import math, random
from lib import *
from home import card
from history import mapa, route, TRIPS
from telemetry import telemetria
from s43 import S, T, th, hline

FR=[]
Y0=30000

def diag(b,p,x0,y0,x1,y1,color,sw,name="risco"):
    a=b.uid("dg"); b.add("vector.create",{"path":f"M{x0} {y0} L{x1} {y1}","x":0,"y":0,"parent":p,"name":name,"select":False},a)
    b.add("node.set",{"id":{"$ref":a},"fill":"none","stroke":{"$var":color},"strokeWeight":sw,"strokeCap":"round"})

def pino_riscado(b,p,cx,cy,size,bg):
    """pino com risco diagonal (sem localização); contorno na cor do fundo para o risco ler sobre o pino"""
    b.icon(p,"pin",cx-size/2,cy-size/2,size,"text2")
    d=size*0.42
    diag(b,p,cx-d,cy-d,cx+d,cy+d,bg,sw=5,name="risco-contorno")
    diag(b,p,cx-d,cy-d,cx+d,cy+d,"text2",sw=2,name="risco")

def borrado(b,p,x,y,w,h,seed,radius,blur,thumb):
    """mapa da localização do proprietário, desfocado (blur); moldura recorta as bordas"""
    wr=b.frame("mapa-desfocado",x,y,w,h,parent=p,fill="map-bg",radius=radius,clip=True)
    pad=14
    m=mapa(b,wr,-pad,-pad,w+2*pad,h+2*pad,seed,thumb=thumb,label=False,trajeto=False)
    b.add("node.set",{"id":m,"effects":[{"type":"layerBlur","radius":blur}]})
    v=b.frame("veu",0,0,w,h,parent=wr,fill="map-bg"); b.add("node.set",{"id":v,"opacity":0.3})
    return wr

def miniatura(b,c,kind,seed):
    """miniatura 72dp no cartão da lista. kind: rota | mapa (desfocado) | semcoord (fallback)"""
    if kind=="rota": mapa(b,c,8,8,72,72,seed,thumb=True); return
    if kind=="mapa":
        wr=borrado(b,c,8,8,72,72,seed,14,5,True)
        pn=b.frame("pino",22,22,28,28,parent=wr,fill="card",stroke="stroke",radius=14)
        b.icon(pn,"pin",5,5,18,"text2")
        return
    t=b.frame("mapa-fallback",8,8,72,72,parent=c,fill="track",radius=14,clip=True)
    pino_riscado(b,t,36,36,30,"track")

def cab(b,f,fs,titulo,menu=False):
    back=b.frame("voltar",8,16,48,48,parent=f); b.icon(back,"back",12,12,24,"text")
    b.text(f,titulo,56,16,size=S(fs,20),weight="Bold",w=W-112,align="center",h=48)
    if menu:
        mn=b.frame("menu",W-56,16,48,48,parent=f)
        for k in range(3): b.frame("ponto",22,15+k*7,4,4,parent=mn,fill="text2",radius=2)

def legenda_quadro(b,f,cur,fs,txt):
    return cur+T(b,f,txt,16,cur,11,fs,w=328,color="text2")+16

# ------------------------------------------------------------ A. Histórico (lista)
def cartao(b,f,y,fs,t,l2,l3,kind,seed):
    if fs<=1:
        h=88; c=card(b,f,"viagem",16,y,328,h)
        miniatura(b,c,kind,seed)
        b.text(c,t,92,10,size=15,weight="SemiBold",w=200)
        b.text(c,l2,92,34,size=14,weight="Bold",w=200,color="primary")
        b.text(c,l3,92,58,size=12,color="text2",w=200)
        b.icon(c,"chev",296,32,24,"text2")
        return h
    w=196; cur=10
    hs=[th(t,15,w,fs),th(l2,14,w,fs,True),th(l3,12,w,fs)]
    h=max(88,10+sum(hs)+4*2+10); c=card(b,f,"viagem",16,y,328,h)
    miniatura(b,c,kind,seed)
    yy=10
    for txt,sz,wt,col,hh in ((t,15,"SemiBold","text",hs[0]),(l2,14,"Bold","primary",hs[1]),(l3,12,"Regular","text2",hs[2])):
        b.text(c,txt,92,yy,size=S(fs,sz),weight=wt,color=col,w=w); yy+=hh+4
    b.icon(c,"chev",296,(h-24)/2,24,"text2")
    return h

def lista(b,x,y,name,fs,coord):
    f=b.frame(name,x,y,W,100,fill="bg",clip=True); FR.append(f)
    cab(b,f,fs,"Histórico")
    cur=72
    if fs<=1:
        s=card(b,f,"resumo",16,cur,328,72)
        for j,(v,lab) in enumerate([("13","Viagens"),("86,4 km","Distância"),("5 h 15","Tempo")]):
            b.text(s,v,14+j*108,14,size=18,weight="Bold",w=100)
            b.text(s,lab+" · 30 dias" if j==0 else lab,14+j*108,42,size=11,color="text2",w=104)
        cur+=72+14
    else:
        rh=70; s=card(b,f,"resumo",16,cur,328,rh*3+12)
        for j,(v,lab) in enumerate([("13","Viagens · 30 dias"),("86,4 km","Distância"),("5 h 15","Tempo")]):
            b.text(s,lab,14,6+j*rh,size=S(fs,11),color="text2",w=300); b.text(s,v,14,6+j*rh+32,size=S(fs,18),weight="Bold",w=300)
        cur+=rh*3+12+14
    cur+=T(b,f,"Hoje",20,cur,13,fs,w=200,weight="SemiBold",color="text2")+6
    cur+=cartao(b,f,cur,fs,"Hoje, 18:42","4,8 km · 21 min","média 14 km/h · máx 27 km/h","rota",11)+10
    cur+=cartao(b,f,cur,fs,"Hoje, 08:15","0,0 km · 3 min","sem percurso gravado","mapa" if coord else "semcoord",23)+10
    cur+=cartao(b,f,cur,fs,"Ontem, 19:03","7,9 km · 32 min","média 15 km/h · máx 31 km/h","rota",37)+10
    cap=("Viagem sem percurso (0,0 km): miniatura 72 dp do mapa da localização do proprietário, desfocada, com pino discreto. Viagem com percurso: igual ao atual."
         if coord else "Sem coordenada disponível: fallback neutro (fundo trilho + pino riscado). Nada de mapa nem localização.")
    cur=legenda_quadro(b,f,cur+6,fs,cap)
    b.add("node.set",{"id":f,"height":max(cur,H)}); return max(cur,H)

# ------------------------------------------------------------ B. Detalhe da viagem sem percurso
def detalhe(b,x,y,name,fs,coord):
    f=b.frame(name,x,y,W,100,fill="bg",clip=True); FR.append(f)
    cab(b,f,fs,"Hoje, 08:15",menu=True)
    cur=72; mh=260 if fs<=1 else 300
    if coord: borrado(b,f,16,cur,328,mh,23,20,9,False)
    else:
        m=b.frame("mapa-fallback",16,cur,328,mh,parent=f,fill="track",radius=20,clip=True)
    # legenda sobreposta
    sw_=232 if fs<=1 else 288; ix=16+(328-sw_)/2
    t1="Sem percurso gravado"; t2="GPS sem leitura"; t3="" if coord else "Sem localização para mostrar o mapa"
    hs=[th(t1,13,sw_-24,fs,True),th(t2,11,sw_-24,fs)]+([th(t3,11,sw_-24,fs)] if t3 else [])
    ic=30; sh=12+ic+6+sum(hs)+4*len(hs)+8
    sy=cur+(mh-sh)/2
    sl=b.frame("legenda-sem-percurso",ix,sy,sw_,sh,parent=f,fill="card",stroke="stroke",radius=16,
               effects=[{"type":"dropShadow","color":"#00000040","offset":[0,4],"radius":12}])
    if coord: b.icon(sl,"pin",sw_/2-12,12,24,"text2")
    else: pino_riscado(b,sl,sw_/2,12+ic/2-3,26,"card")
    yy=12+ic+6
    for txt,sz,wt,col,hh in [(t1,13,"SemiBold","text",hs[0]),(t2,11,"Regular","text2",hs[1])]+([(t3,11,"Regular","text2",hs[2])] if t3 else []):
        b.text(sl,txt,12,yy,size=S(fs,sz),weight=wt,color=col,w=sw_-24,align="center"); yy+=hh+4
    cur+=mh+14
    items=[("route","Distância","0,0 km"),("clock","Duração","3 min"),("speed","Velocidade média","0 km/h"),("trend","Velocidade máxima","0 km/h"),("mountain","Altimetria","+0 m"),("bolt","Consumo médio","--")]
    if fs<=1:
        c=card(b,f,"estatisticas",16,cur,328,176)
        for k,(i_,lab,val) in enumerate(items):
            cx0=14+(k%2)*160; cy0=14+(k//2)*54
            tl=b.frame("ic-tile",cx0,cy0,36,36,parent=c,fill="track",radius=10); b.icon(tl,i_,6,6,24,"primary")
            b.text(c,lab,cx0+44,cy0,size=11,color="text2",w=108); b.text(c,val,cx0+44,cy0+16,size=16,weight="Bold",w=108)
        cur+=176+14
    else:
        rh=84; c=card(b,f,"estatisticas",16,cur,328,12+rh*6)
        for k,(i_,lab,val) in enumerate(items):
            cy0=12+k*rh
            tl=b.frame("ic-tile",14,cy0+14,36,36,parent=c,fill="track",radius=10); b.icon(tl,i_,6,6,24,"primary")
            b.text(c,lab,62,cy0,size=S(fs,11),color="text2",w=256); b.text(c,val,62,cy0+30,size=S(fs,16),weight="Bold",w=256)
        cur+=12+rh*6+14
    cur+=T(b,f,"Trajetos e viagens ficam salvos só no seu celular.",16,cur,12,fs,w=328,color="text2",align="center")+20
    b.add("node.set",{"id":f,"height":cur}); return cur

# ------------------------------------------------------------ C. Ícone GPS no velocímetro
def celula_icone(b,p,cx,y,kind,fs):
    """slot 40 dp centrado em cx; kind: scooter | gps-antes | gps-depois"""
    if kind=="scooter": b.icon(p,"scooter",cx-20,y,40,"primary",sw=1.6)
    elif kind=="gps-antes": b.icon(p,"gps",cx-20,y,40,"primary",sw=1.6)
    else:
        ring=b.frame("borda-gps",cx-20,y,40,40,parent=p,stroke="primary",radius=20,sw=1.5)
        b.icon(ring,"gps",6,6,28,"primary",sw=1.6)

def comparacao_gps(b,x,y,name,fs):
    f=b.frame(name,x,y,W,100,fill="bg",clip=True); FR.append(f)
    cur=14
    cur+=T(b,f,"Velocímetro · ícone da fonte de velocidade",16,cur,14,fs,w=328,weight="Bold")+10
    cols=[("scooter","Patinete","40 dp"),("gps-antes","GPS (antes)","40 dp, parece maior"),("gps-depois","GPS (depois)","28 dp numa borda de 40 dp")]
    cw=328/3
    if fs>1: cw=328   # fonte 2,0: uma coluna por vez
    if fs<=1:
        c=card(b,f,"comparacao",16,cur,328,190)
        for i,(k,t1,t2) in enumerate(cols):
            cx=cw*i+cw/2
            T(b,c,t1,cw*i+4,14,12,fs,w=cw-8,weight="SemiBold",align="center")
            b.frame("slot-40dp",cx-20,44,40,40,parent=c,stroke="stroke",radius=4,sw=1)
            celula_icone(b,c,cx,44,k,fs)
            for j in (0,1): b.frame("pagina-%d"%j,cx-11+j*14,100,8,8,parent=c,fill="primary" if (j==0)==(k=="scooter") else "track",radius=4)
            T(b,c,t2,cw*i+4,122,11,fs,w=cw-8,color="text2",align="center")
        cur+=190+14
    else:
        rh=150; c=card(b,f,"comparacao",16,cur,328,rh*3+10)
        for i,(k,t1,t2) in enumerate(cols):
            yy=10+i*rh
            T(b,c,t1,14,yy,12,fs,w=300,weight="SemiBold")
            b.frame("slot-40dp",14,yy+54,40,40,parent=c,stroke="stroke",radius=4,sw=1)
            celula_icone(b,c,34,yy+54,k,fs)
            for j in (0,1): b.frame("pagina-%d"%j,70+j*14,yy+70,8,8,parent=c,fill="primary" if (j==0)==(k=="scooter") else "track",radius=4)
            T(b,c,t2,14,yy+100,11,fs,w=300,color="text2")
        cur+=rh*3+10+14
    cur=legenda_quadro(b,f,cur,fs,"Quadrado fino = slot de 40 dp (só nesta comparação). Proposta: GPS 28 dp dentro de borda circular 40 dp, traço 1,5, cor primária; patinete inalterado.")
    b.add("node.set",{"id":f,"height":cur}); return cur

# ------------------------------------------------------------ E. Consumo em escala absoluta (W, 0–750)
SER=[round(v*12.5) for v in (22,24,27,31,36,42,47,51,55,52,48,44,40,37,35,38,43,49,54,58,60,56,50,45,41,38,36)]   # 27 amostras em W
TETO=750

def consumo_abs(b,p,x,y,fs,off,mark):
    """24 barras = SER[off:off+24]. mark = índice absoluto da amostra marcada com ponto."""
    v=SER[off:off+24]; cur_=v[-1]; txt=f"{cur_} W"
    if fs<=1:
        h=92; c=card(b,p,"cartao-consumo-abs",x,y,328,h)
        b.text(c,"Consumo em tempo real",14,10,size=12,color="text2",w=200)
        b.text(c,txt,14,28,size=24,weight="Bold",w=150,h=36)
        b.text(c,"escala fixa 0–750 W",14,66,size=11,color="text2",w=150)
        cx,cw,base,ch=196,122,70,48; pitch=5.2; bw=3.4; foot=74; fsz=10; lab=(0,14)
    else:
        h=320; c=card(b,p,"cartao-consumo-abs",x,y,328,h)
        b.text(c,"Consumo em tempo real",14,10,size=S(2,12),color="text2",w=300)
        b.text(c,txt,14,42,size=S(2,24),weight="Bold",w=300,h=54)
        b.text(c,"escala fixa 0–750 W",14,98,size=S(2,11),color="text2",w=300)
        cx,cw,base,ch=44,270,262,100; pitch=11.2; bw=7; foot=268; fsz=S(2,10); lab=(0,26)
    for g in (TETO,TETO//2):
        gy=base-g/TETO*ch
        hline(b,c,cx-4,cx+cw+4,gy,"stroke",1,[3,3] if g!=TETO else None,"grade-%d"%g)
        b.text(c,str(g),cx-34,gy-lab[1]/2,size=S(fs,10),color="text2",w=30,align="right",h=lab[1])
    for i,val in enumerate(v):
        hh=max(2,min(ch,val/TETO*ch)); b.frame("barra",cx+i*pitch,base-hh,bw,hh,parent=c,fill="primary",radius=min(2,bw/2))
        if off+i==mark:
            b.frame("marca",cx+i*pitch+bw/2-2,base-hh-8,4,4,parent=c,fill="text",radius=2)
    b.text(c,"últimos 30 s",cx,foot,size=fsz,color="text2",w=cw)
    return h

def quadro_consumo(b,x,y,name,fs,off,titulo):
    f=b.frame(name,x,y,W,100,fill="bg",clip=True); FR.append(f)
    cur=14
    cur+=T(b,f,titulo,16,cur,14,fs,w=328,weight="Bold")+8
    cur+=consumo_abs(b,f,16,cur,fs,off,10)+14
    cur=legenda_quadro(b,f,cur,fs,"Eixo fixo 0–750 W (grades em 375 e 750). Cada barra mantém a altura do consumo daquele instante e só se desloca para a esquerda; o ponto marca a mesma amostra nos dois quadros.")
    b.add("node.set",{"id":f,"height":cur}); return cur

# ------------------------------------------------------------ montagem
def s45_frames(b,setm):
    y=Y0; names=[]
    # A, B, C, E nas duas fontes
    for fs in (1,2):
        suf="fonte2-" if fs==2 else ""
        x=0; hm=0
        for fn,nm in ((lambda n,fs=fs:lista(b,x,y,n,fs,True),f"historico-sem-percurso-{suf}v1"),
                      (lambda n,fs=fs:lista(b,x,y,n,fs,False),f"historico-sem-coordenada-{suf}v1"),
                      (lambda n,fs=fs:detalhe(b,x,y,n,fs,True),f"viagem-sem-percurso-mapa-{suf}v1"),
                      (lambda n,fs=fs:detalhe(b,x,y,n,fs,False),f"viagem-sem-percurso-sem-coordenada-{suf}v1"),
                      (lambda n,fs=fs:comparacao_gps(b,x,y,n,fs),f"velocimetro-icone-gps-{suf}v1"),
                      (lambda n,fs=fs:quadro_consumo(b,x,y,n,fs,0,"Consumo · quadro t"),f"consumo-absoluto-t-{suf}v1"),
                      (lambda n,fs=fs:quadro_consumo(b,x,y,n,fs,3,"Consumo · quadro t + 3 amostras"),f"consumo-absoluto-t3-{suf}v1")):
            h=fn(nm); names.append(nm); x+=400; hm=max(hm,h)
        y+=hm+60
    # D. Parar desabilitado (telemetria com barra fixa; fonte 1,0 apenas)
    for nm,kw in (("telemetria-parar-gravando-v1",dict(speed=18,fixed=True)),("telemetria-parar-desabilitado-v1",dict(fixed=True,parar_off=True))):
        f=telemetria(b,(len(names)-14)*400,y,nm,**kw); FR.append(f); names.append(nm)
    for f in FR: setm(f)
    FR.clear()
    return names
