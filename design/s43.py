"""S43: consumo em escala absoluta, detalhe da viagem com modo/motor, quadro LocationOff.
Só tokens existentes de lib.py. fs=1 (normal) ou 2 (fonte 2,0; títulos grandes atenuados como na escala não linear do Android)."""
import math, random
from lib import *
from home import card
from history import route, TRIPS
from screens import status

def S(fs,size):
    if fs<=1: return size
    return size*2.0 if size<=15 else 30+(size-15)*1.0

def th(t,size,w,fs=1,bold=False):
    """altura estimada de um texto com quebra automática"""
    sz=S(fs,size); cw=sz*(0.58 if bold else 0.53); n=0
    for ln in t.split("\n"): n+=max(1,math.ceil(len(ln)*cw/w))
    return math.ceil(n*sz*1.25)

def T(b,p,t,x,y,size,fs=1,w=None,**kw):
    b.text(p,t,x,y,size=S(fs,size),w=w,**kw)
    return th(t,size,w or 300,fs,kw.get("weight")=="Bold")

def hline(b,p,x0,x1,y,color,sw=1,dashes=None,name="linha"):
    a=b.uid("ln"); b.add("vector.create",{"path":f"M{x0} {y} L{x1} {y}","x":0,"y":0,"parent":p,"name":name,"select":False},a)
    q={"id":{"$ref":a},"fill":"none","stroke":{"$var":color},"strokeWeight":sw}
    if dashes: q["dashes"]=dashes
    b.add("node.set",q)

# ---------------------------------------------------------------- 1. Consumo
FR=[]
STEPS=[250,500,750,1000]
def serie(estado):
    r=random.Random(11); n=24
    if estado=="parado": return [0]*n,0
    if estado=="sem": return None,None
    if estado=="60w": v=[60+r.choice([-3,-2,-1,0,1,2,3]) for _ in range(n)]; v[-1]=60; return v,7.5
    base=[0,10,40,80,120,170,220,260,300,340,380,410,440,470,500,520,540,560,575,590,600,605,598,600]
    s=[max(0,v+(r.randint(-12,12) if 0<v<600 else 0)) for v in base]; s[-1]=600; return s,27.3
def teto_adaptativo(pico,cap=250):
    for st in STEPS:
        if pico<=st*0.9: return st       # histerese: sobe quando o pico passa de 90% do degrau; nunca desce na viagem
    return STEPS[-1]

def consumo(b,p,x,y,fs,estado,modo):
    """modo: 'fixa' (0–750 W) | 'adaptativa' (250/500/750/1000 W). retorna altura do card"""
    s,whkm=serie(estado)
    watt=None if s is None else s[-1]
    cap=750 if modo=="fixa" else (250 if s is None else teto_adaptativo(max(s)))
    sub={"parado":"Parado · sem consumo","sem":"Sem dado do patinete"}.get(estado) or f"{whkm:.1f} Wh/km".replace(".",",")
    if fs<=1:
        h=92; c=card(b,p,"cartao-consumo-"+estado,x,y,328,h)
        b.text(c,"Consumo em tempo real",14,10,size=12,color="text2",w=200)
        b.text(c,"-- W" if watt is None else f"{watt} W",14,28,size=28,weight="Bold",w=140,h=36,color="text2" if watt is None else "text")
        b.text(c,sub,14,66,size=12,color="text2",w=150)
        cx,cw,base,ch=168,150,70,44; pitch=6.4; bw=4
        b.text(c,f"{cap} W",cx,7,size=10,color="text2",w=cw,align="right",h=14)
        cap_y=base-ch; foot=74; fsz=10
    else:
        h=300; c=card(b,p,"cartao-consumo-"+estado,x,y,328,h)
        b.text(c,"Consumo em tempo real",14,10,size=S(2,12),color="text2",w=300)
        b.text(c,"-- W" if watt is None else f"{watt} W",14,42,size=S(2,28),weight="Bold",w=300,h=54,color="text2" if watt is None else "text")
        b.text(c,sub,14,98,size=S(2,12),color="text2",w=300)
        cx,cw,base,ch=14,300,238,84; pitch=12.5; bw=8
        b.text(c,f"{cap} W",cx,128-4,size=S(2,10),color="text2",w=cw,align="right",h=24)
        cap_y=base-ch; foot=244; fsz=S(2,10)
    # teto (guia tênue) + barras proporcionais a W/teto
    hline(b,c,cx-4,cx+cw+4,cap_y,"stroke",1,name="guia-teto")
    for i in range(24):
        if s is None: hh=2; col="track"
        else: hh=max(2,min(ch,s[i]/cap*ch)); col="primary" if s[i]>0 else "track"
        b.frame("barra",cx+i*pitch,base-hh,bw,hh,parent=c,fill=col,radius=min(2,bw/2))
    if watt is not None:   # referência do valor atual, tracejada
        hline(b,c,cx-4,cx+cw+4,base-min(ch,watt/cap*ch),"text2",1,[3,3],"referencia-atual")
    b.text(c,"últimos 30 s",cx,foot,size=fsz,color="text2",w=cw)
    return h

def quadro_consumo(b,x,y,name,fs,modo):
    titulo={"fixa":"Escala fixa 0–750 W","adaptativa":"Escala em degraus (só sobe)"}[modo]
    cap={"fixa":"Barra = W ÷ 750. Linha tracejada = valor atual. Fio claro = teto.",
         "adaptativa":"Teto 250/500/750/1000 W; sobe ao passar de 90% do degrau e não desce até zerar a viagem."}[modo]
    f=b.frame(name,x,y,W,100,fill="bg",clip=True); FR.append(f)
    cur=14
    cur+=T(b,f,"Consumo · "+titulo,16,cur,14,fs,w=328,weight="Bold")+8
    rot={"parado":"Parado, 0 W","60w":"60 W constantes (ruído ±3 W)","acel":"Aceleração até ~600 W","sem":"Sem dado"}
    for est in ("parado","60w","acel","sem"):
        cur+=T(b,f,rot[est],20,cur,12,fs,w=320,color="text2")+4
        cur+=consumo(b,f,16,cur,fs,est,modo)+14
    cur+=T(b,f,cap,16,cur,11,fs,w=328,color="text2")+16
    b.add("node.set",{"id":f,"height":cur})
    return cur

# ---------------------------------------------------------------- 2. Detalhe da viagem
MODOS=["Walking","Eco","Sport","Race"]
EST={"Walking":("success",5,[1,9],"round",12),"Eco":("primary",5,[10,6],"none",20),"Sport":("pending",6,None,"round",28),"Race":("error",8,None,"round",36),
     "2 motores":("primary",7,None,"round",36),"1 motor":("pending",4,[8,5],"none",20)}
T_MODO=[("Walking",4),("Eco",6),("Sport",5),("Race",3),("Sport",3)]
T_MOTOR=[("2 motores",8),("1 motor",5),("2 motores",8)]
INICIO=18*60+42

def hhmm(m): return f"{(m//60)%24:02d}:{m%60:02d}"
def trocas(seg):
    out=[]; t=0
    for i,(n,d) in enumerate(seg):
        if i>0: out.append((t,seg[i-1][0],n))
        t+=d
    return out

def amostra(pts,fr):
    L=[0]
    for a,c in zip(pts,pts[1:]): L.append(L[-1]+math.dist(a,c))
    alvo=fr*L[-1]
    for i in range(1,len(pts)):
        if L[i]>=alvo or i==len(pts)-1:
            u=0 if L[i]==L[i-1] else (alvo-L[i-1])/(L[i]-L[i-1]); u=min(max(u,0),1)
            return (pts[i-1][0]+u*(pts[i][0]-pts[i-1][0]),pts[i-1][1]+u*(pts[i][1]-pts[i-1][1]))
def sub(pts,f0,f1):
    L=[0]
    for a,c in zip(pts,pts[1:]): L.append(L[-1]+math.dist(a,c))
    tot=L[-1]; out=[amostra(pts,f0)]
    for i,pt in enumerate(pts):
        if f0*tot<L[i]<f1*tot: out.append(pt)
    out.append(amostra(pts,f1)); return out

def path(b,p,pts,color,sw,dashes=None,cap="round",name="seg",glow=False):
    d="M"+" L".join(f"{px:.1f} {py:.1f}" for px,py in pts)
    a=b.uid("sg"); b.add("vector.create",{"path":d,"x":0,"y":0,"parent":p,"name":name,"select":False},a)
    q={"id":{"$ref":a},"fill":"none","stroke":{"$var":color},"strokeWeight":sw,"strokeCap":cap,"strokeJoin":"round"}
    if dashes: q["dashes"]=dashes
    b.add("node.set",q)

def mapa2(b,p,x,y,w,h,seed,seg=None,vazio=False):
    m=b.frame("mapa",x,y,w,h,parent=p,fill="map-bg",radius=20,clip=True)
    b.frame("parque",w*0.08,h*0.12,w*0.34,h*0.26,parent=m,fill="map-park",radius=10)
    b.frame("agua",w*0.62,h*0.62,w*0.5,h*0.5,parent=m,fill="map-water",radius=40)
    for fy in (0.22,0.45,0.7,0.88): b.frame("rua-h",0,h*fy,w,6,parent=m,fill="map-road")
    for fx in (0.18,0.4,0.58,0.8): b.frame("rua-v",w*fx,0,6,h,parent=m,fill="map-road")
    if vazio:
        sc=b.frame("veu",0,0,w,h,parent=m,fill="scrim"); b.add("node.set",{"id":sc,"opacity":0.35})
        pl=b.frame("selo",w/2-80,h/2-60,160,120,parent=m,fill="card",stroke="stroke",radius=16)
        b.icon(pl,"pin",8,8,24,"text2"); b.text(pl,"Sem trajeto gravado",8,38,size=13,weight="SemiBold",w=144,align="center")
        b.text(pl,"GPS sem leitura",8,84,size=11,color="text2",w=144,align="center")
        return m
    pts=route(seed,w,h); tot=sum(d for _,d in seg) if seg else 1
    if not seg:
        path(b,m,pts,"card",9,name="contorno"); path(b,m,pts,"primary",5,name="trajeto")
        sx,sy=pts[0]; ex,ey=pts[-1]
    else:
        t=0; fr=[]
        for n,d in seg: fr.append((n,t/tot,(t+d)/tot)); t+=d
        for n,f0,f1 in fr: path(b,m,sub(pts,f0,f1),"card",EST[n][1]+4,name="contorno")
        for n,f0,f1 in fr:
            col,sw,da,cap,_=EST[n]; path(b,m,sub(pts,f0,f1),col,sw,da,cap,name="seg-"+n)
        sx,sy=pts[0]; ex,ey=pts[-1]
    b.frame("inicio",sx-7,sy-7,14,14,parent=m,fill="text",stroke="card",radius=7,sw=2)
    b.frame("fim",ex-8,ey-8,16,16,parent=m,fill="card",stroke="text",radius=8,sw=3)
    if seg:
        t=0
        for i,(n,d) in enumerate(seg):
            if i>0:
                px,py=amostra(pts,t/tot); col=EST[n][0]
                mk=b.frame("troca-%d"%i,px-10,py-10,20,20,parent=m,fill=col,stroke="card",radius=10,sw=2)
                b.text(mk,str(i),0,0,size=11,weight="Bold",color="on-color",w=20,align="center",h=20)
            t+=d
    return m

def seletor(b,p,x,y,w,h,sel,fs,off=False):
    s=b.frame("seletor-colorir",x,y,w,h,parent=p,fill="track",radius=h//2)
    sw=(w-8)/3
    for i,l in enumerate(("Modo","Motor","Nenhum")):
        on=i==sel
        if on: b.frame("seg-sel",4+i*sw,4,sw,h-8,parent=s,fill="primary",radius=(h-8)//2)
        col="on-color" if on else ("on-disabled" if off else "text")
        b.text(s,l,4+i*sw,0,size=(14 if fs<=1 else 22),color=col,w=sw,weight="SemiBold" if on else "Regular",align="center",h=h,name="seg-"+l)
    return s

def traco(b,p,x,y,nome,fs,w=34):
    """amostra do traço do mapa (mesmo padrão): cor + espessura + pontilhado/tracejado"""
    col,sw,da,cap,_=EST[nome]; path(b,p,[(x,y),(x+w,y)],col,sw,da,cap,name="legenda-traco")

def detalhe_modo(b,x,y,name,fs=1,view="modo",dados="ok"):
    """view: modo|motor|nenhum; dados: ok|unico|sem|semtrajeto"""
    f=b.frame(name,x,y,W,100,fill="bg",clip=True); FR.append(f)
    back=b.frame("voltar",8,16,48,48,parent=f); b.icon(back,"back",12,12,24,"text")
    b.text(f,"Hoje, 18:42",56,16,size=S(fs,20),weight="Bold",w=W-112,align="center",h=48)
    mn=b.frame("menu",W-56,16,48,48,parent=f)
    for k in range(3): b.frame("ponto",22,15+k*7,4,4,parent=mn,fill="text2",radius=2)
    cur=72
    sem=dados=="sem"
    if sem: view="nenhum"
    cur+=T(b,f,"Colorir trajeto por",20,cur,12,fs,w=320,color="text2")+4
    hh=48 if fs<=1 else 66
    seletor(b,f,16,cur,328,hh,{"modo":0,"motor":1,"nenhum":2}[view],fs,off=sem); cur+=hh+10
    mh=260 if fs<=1 else 280
    seg=None
    if not sem and view!="nenhum":
        seg=(T_MODO if view=="modo" else T_MOTOR)
        if dados=="unico": seg=[("Eco",21)] if view=="modo" else [("2 motores",21)]
    mapa2(b,f,16,cur,328,mh,TRIPS[0][7],seg,vazio=dados=="semtrajeto"); cur+=mh+10
    nomes=[] if seg is None else list(dict.fromkeys(n for n,_ in seg))
    ordem=MODOS if view=="modo" else ["2 motores","1 motor"]
    # legenda
    if seg:
        leg=[n for n in ordem if n in nomes] if dados=="unico" else ordem
        col2=fs<=1 and len(leg)>2; rowh=28 if fs<=1 else 44
        lh=12+rowh*(math.ceil(len(leg)/2) if col2 else len(leg))+(0 if dados!="ok" else 0)
        c=card(b,f,"legenda",16,cur,328,lh)
        for i,n in enumerate(leg):
            lx=14+(i%2)*160 if col2 else 14; ly=6+(i//2 if col2 else i)*rowh
            traco(b,c,lx+2,ly+rowh/2,n,fs,30)
            b.text(c,n,lx+42,ly,size=S(fs,13),w=108 if col2 else 240,h=rowh,color="text")
        cur+=lh+10
        # linha do tempo
        tl_total=sum(d for _,d in seg); tr=trocas(seg)
        lt=[]  # alturas calculadas depois; criamos o card com altura provisória
        ch0=cur
        c=b.frame("linha-do-tempo",16,cur,328,100,parent=f,fill="card",stroke="stroke",radius=16)
        yy=12; yy+=T(b,c,"Linha do tempo",14,yy,13,fs,w=300,weight="SemiBold")+8
        base=yy+(38 if fs<=1 else 44); bx,bw=16,296; scale=1 if fs<=1 else 1.2
        t=0
        for n,d in seg:
            wd=bw*d/tl_total; h_=EST[n][4]*scale
            b.frame("faixa-"+n,bx+t,base-h_,max(wd-2,2),h_,parent=c,fill=EST[n][0],radius=4)
            t+=wd
        yy=base+6; dm=18 if fs<=1 else 26; acc=0
        for i,(tt,_,_) in enumerate(tr):
            px=bx+bw*tt/tl_total
            nn=tr[i][2]; mk=b.frame("tick-%d"%(i+1),px-dm/2,yy,dm,dm,parent=c,fill=EST[nn][0],radius=dm/2)
            b.text(mk,str(i+1),0,0,size=11 if fs<=1 else 15,weight="Bold",color="on-color",w=dm,align="center",h=dm)
        yy+=(dm+4) if tr else 2
        t1=T(b,c,hhmm(INICIO),bx-2,yy,11,fs,w=90,color="text2")
        b.text(c,hhmm(INICIO+tl_total),bx+bw-90+2,yy,size=S(fs,11),color="text2",w=90,align="right")
        yy+=t1+8
        if tr:
            for i,(tt,a_,b_) in enumerate(tr):
                mk=b.frame("lista-%d"%(i+1),14,yy+1,dm,dm,parent=c,fill=EST[b_][0],radius=dm/2)
                b.text(mk,str(i+1),0,0,size=11 if fs<=1 else 15,weight="Bold",color="on-color",w=dm,align="center",h=dm)
                txt=f"{hhmm(INICIO+tt)}  {a_} → {b_}"
                hh_=T(b,c,txt,14+dm+8,yy,12,fs,w=328-14-dm-8-10)
                yy+=max(hh_,dm)+6
        else:
            yy+=T(b,c,"Sem trocas: "+(seg[0][0])+" durante toda a viagem.",14,yy,12,fs,w=300,color="text2")+6
        b.add("node.set",{"id":c,"height":yy+8}); cur+=yy+8+10
        # resumo
        tot_min=tl_total; ag={}
        for n,d in seg: ag[n]=ag.get(n,0)+d
        titulo="Tempo em cada modo" if view=="modo" else "Tempo com cada motor"
        c=b.frame("resumo-tempo",16,cur,328,100,parent=f,fill="card",stroke="stroke",radius=16)
        yy=12; yy+=T(b,c,titulo,14,yy,13,fs,w=300,weight="SemiBold")+8
        for n in ordem:
            if n not in ag: continue
            m_=ag[n]; pc=round(100*m_/tot_min); txt=f"{m_} min · {pc} %"
            col=EST[n][0]
            b.frame("chip",14,yy+3,12,12 if fs<=1 else 20,parent=c,fill=col,radius=3)
            if fs<=1:
                b.text(c,n,34,yy,size=13,weight="SemiBold",w=140)
                b.text(c,txt,150,yy,size=13,color="text2",w=164,align="right")
                yy+=22
            else:
                b.text(c,n,40,yy,size=S(fs,13),weight="SemiBold",w=270); yy+=th(n,13,270,fs)+2
                b.text(c,txt,40,yy,size=S(fs,13),color="text2",w=270); yy+=th(txt,13,270,fs)+4
            b.frame("trilho",14,yy,300,6,parent=c,fill="track",radius=3)
            b.frame("parte",14,yy,max(300*m_/tot_min,6),6,parent=c,fill=col,radius=3)
            yy+=16
        b.add("node.set",{"id":c,"height":yy+2}); cur+=yy+2+10
    elif sem:
        c=card(b,f,"sem-dado",16,cur,328,100)
        y2=12; y2+=T(b,c,"Modo não registrado",14,y2,15,fs,w=300,weight="Bold")+6
        y2+=T(b,c,"Esta viagem foi gravada sem a leitura de modo e motor do patinete. O trajeto aparece sem cores.",14,y2,12,fs,w=300,color="text2")
        b.add("node.set",{"id":c,"height":y2+14}); cur+=y2+14+10
    # estatísticas (como hoje)
    items=[("route","Distância",TRIPS[0][1]),("clock","Duração",TRIPS[0][2]),("speed","Velocidade média",TRIPS[0][3]),("trend","Velocidade máxima",TRIPS[0][4]),("mountain","Altimetria",TRIPS[0][5]),("bolt","Consumo médio",TRIPS[0][6])]
    if fs<=1:
        c=card(b,f,"estatisticas",16,cur,328,176)
        for k,(ic,lab,val) in enumerate(items):
            cx0=14+(k%2)*160; cy0=14+(k//2)*54
            tl=b.frame("ic-tile",cx0,cy0,36,36,parent=c,fill="track",radius=10); b.icon(tl,ic,6,6,24,"primary")
            b.text(c,lab,cx0+44,cy0,size=11,color="text2",w=108); b.text(c,val,cx0+44,cy0+16,size=16,weight="Bold",w=108)
        cur+=176+14
    else:
        rh=84; c=card(b,f,"estatisticas",16,cur,328,12+rh*6)
        for k,(ic,lab,val) in enumerate(items):
            cy0=12+k*rh
            tl=b.frame("ic-tile",14,cy0+14,36,36,parent=c,fill="track",radius=10); b.icon(tl,ic,6,6,24,"primary")
            b.text(c,lab,62,cy0,size=S(fs,11),color="text2",w=256); b.text(c,val,62,cy0+30,size=S(fs,16),weight="Bold",w=256)
        cur+=12+rh*6+14
    cur+=T(b,f,"Trajetos e viagens ficam salvos só no seu celular.",16,cur,12,fs,w=328,color="text2",align="center")+20
    b.add("node.set",{"id":f,"height":cur}); return cur

# ---------------------------------------------------------------- montagem
def s43_frames(b,setm):
    y=6600
    row=[]
    for fs in (1,2):
        x=0; hmax=0
        for modo,nm in (("fixa","consumo-escala-fixa"),("adaptativa","consumo-escala-adaptativa")):
            name=f"{nm}-{'fonte2-' if fs==2 else ''}v1"
            f_h=quadro_consumo(b,x,y,name,fs,modo)
            row.append(name); x+=400; hmax=max(hmax,f_h)
        y+=hmax+60
    # detalhe da viagem
    casos=[("Modo","modo","ok"),("Motor","motor","ok"),("Nenhum","nenhum","ok"),("Modo-Unico","modo","unico"),("Sem-Modo","modo","sem"),("Sem-Trajeto","modo","semtrajeto")]
    for fs in (1,2):
        x=0; hmax=0
        for nm,v,d in casos:
            name=f"viagem-{nm}-{'fonte2-' if fs==2 else ''}v1".lower()
            h_=detalhe_modo(b,x,y,name,fs,v,d); row.append(name); x+=400; hmax=max(hmax,h_)
        y+=hmax+60
    # LocationOff
    st=status(b,0,y,"recuperacao-localizacao","pin","pending","Localização desligada","No Android anterior ao 12, o app só consegue procurar o patinete por Bluetooth com a localização do aparelho ligada.","Ativar localização","primary","Agora não")
    FR.append(st)
    for f in FR: setm(f)
    FR.clear()
    return row
