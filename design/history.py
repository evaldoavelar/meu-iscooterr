import random
from lib import *
from home import card

def dots(b,p,x,y,color="text2"):
    for k in range(3): b.frame("ponto",x,y+k*7,4,4,parent=p,fill=color,radius=2)

def header(b,f,name,title,menu=False):
    back=b.frame("voltar",8,16,48,48,parent=f); b.icon(back,"back",12,12,24,"text"); b.refs[name+":voltar"]=back
    b.text(f,title,56,20,size=20,weight="Bold",w=W-112,align="center",h=40)
    if menu:
        m=b.frame("menu",W-56,16,48,48,parent=f); dots(b,m,22,15); b.refs[name+":menu"]=m

def route(seed,w,h,n=12,pad=0.12):
    r=random.Random(seed); x,y=pad,0.78; pts=[(x,y)]
    for i in range(n):
        x=min(1-pad,x+r.uniform(0.02,(1-2*pad)/n*1.8)); y=min(0.86,max(0.14,y+r.uniform(-0.13,0.07)))
        pts.append((x,y))
    return [(px*w,py*h) for px,py in pts]

def mapa(b,p,x,y,w,h,seed,thumb=False,label=True):
    m=b.frame("mapa",x,y,w,h,parent=p,fill="map-bg",radius=14 if thumb else 20,clip=True)
    r=random.Random(seed)
    # parque e água
    b.frame("parque",w*0.08,h*0.12,w*0.34,h*0.26,parent=m,fill="map-park",radius=10)
    b.frame("agua",w*0.62,h*0.62,w*0.5,h*0.5,parent=m,fill="map-water",radius=40)
    rw=2 if thumb else 6
    for i,fy in enumerate((0.22,0.45,0.7,0.88) if not thumb else (0.3,0.62)):
        b.frame("rua-h",0,h*fy,w,rw,parent=m,fill="map-road")
    for i,fx in enumerate((0.18,0.4,0.58,0.8) if not thumb else (0.3,0.66)):
        b.frame("rua-v",w*fx,0,rw,h,parent=m,fill="map-road")
    pts=route(seed,w,h)
    d="M"+" L".join(f"{px:.1f} {py:.1f}" for px,py in pts)
    a=b.uid("rt"); b.add("vector.create",{"path":d,"x":0,"y":0,"parent":m,"name":"trajeto","select":False},a)
    b.add("node.set",{"id":{"$ref":a},"fill":"none","stroke":{"$var":"primary"},"strokeWeight":3 if thumb else 5,"strokeCap":"round","strokeJoin":"round",
                      "effects":[{"type":"dropShadow","color":"#00E5FF55","offset":[0,0],"radius":8}]})
    s=5 if thumb else 8
    b.frame("inicio",pts[0][0]-s,pts[0][1]-s,2*s,2*s,parent=m,fill="success",stroke="card",radius=s,sw=2)
    b.frame("fim",pts[-1][0]-s,pts[-1][1]-s,2*s,2*s,parent=m,fill="primary",stroke="card",radius=s,sw=2)
    if label and not thumb: b.text(m,"Mapa de exemplo",12,h-24,size=11,color="text2",w=140)
    return m

TRIPS=[("Hoje, 18:42","4,8 km","21 min","14 km/h","27 km/h","+18 m","24 Wh/km",11,"Hoje"),
       ("Hoje, 08:15","3,1 km","14 min","13 km/h","25 km/h","+6 m","22 Wh/km",23,None),
       ("Ontem, 19:03","7,9 km","32 min","15 km/h","31 km/h","+42 m","27 Wh/km",37,"Ontem"),
       ("Sáb 31, 18:20","5,2 km","24 min","13 km/h","26 km/h","+12 m","23 Wh/km",41,"Semana passada"),
       ("Qui 29, 10:05","12,6 km","48 min","16 km/h","34 km/h","+75 m","28 Wh/km",59,None)]

def historico(b,x,y,name,vazio=False):
    f=b.frame(name,x,y,W,H,fill="bg",clip=True)
    header(b,f,name,"Histórico")
    if vazio:
        c=b.frame("vazio",80,250,200,200,parent=f)
        t=b.frame("ic",70,0,60,60,parent=c,fill="track",radius=30); b.icon(t,"route",12,12,36,"primary")
        b.text(c,"Nenhuma viagem ainda",0,80,size=17,weight="Bold",w=200,align="center")
        b.text(c,"Suas viagens aparecem aqui, com o trajeto no mapa. Tudo fica só no seu celular.",0,108,size=13,color="text2",w=200,align="center")
        return f
    s=card(b,f,"resumo",16,68,328,72)
    for j,(v,lab) in enumerate([("12","Viagens"),("86,4 km","Distância"),("5 h 12","Tempo")]):
        b.text(s,v,14+j*108,14,size=18,weight="Bold",w=100)
        b.text(s,lab+" · 30 dias" if j==0 else lab,14+j*108,42,size=11,color="text2",w=104)
    yy=154
    for i,(t,dist,dur,avg,mx,alt,cons,seed,sec) in enumerate(TRIPS):
        if sec: 
            b.text(f,sec,20,yy,size=13,weight="SemiBold",color="text2",w=200); yy+=24
        c=card(b,f,"viagem-%d"%i,16,yy,328,88)
        b.refs[f"{name}:viagem-{i}"]=c
        mapa(b,c,8,8,72,72,seed,thumb=True)
        b.text(c,t,92,10,size=15,weight="SemiBold",w=200)
        b.text(c,f"{dist} · {dur}",92,34,size=14,weight="Bold",w=200,color="primary")
        b.text(c,f"média {avg} · máx {mx}",92,58,size=12,color="text2",w=200)
        b.icon(c,"trash",296,8,24,"text2"); b.refs[f"{name}:excluir-{i}"]=b.frame("excluir-%d"%i,284,0,44,44,parent=c)
        b.icon(c,"chev",296,52,24,"text2")
        yy+=98
    return f

def detalhe(b,x,y,name,trip=0):
    t,dist,dur,avg,mx,alt,cons,seed,_=TRIPS[trip]
    f=b.frame(name,x,y,W,H,fill="bg",clip=True)
    header(b,f,name,t,menu=True)
    mapa(b,f,16,68,328,300,seed)
    c=card(b,f,"estatisticas",16,384,328,176)
    items=[("route","Distância",dist),("clock","Duração",dur),("speed","Velocidade média",avg),("trend","Velocidade máxima",mx),("mountain","Altimetria",alt),("bolt","Consumo médio",cons)]
    for k,(ic,lab,val) in enumerate(items):
        cx0=14+(k%2)*160; cy0=14+(k//2)*54
        tl=b.frame("ic-tile",cx0,cy0,36,36,parent=c,fill="track",radius=10); b.icon(tl,ic,6,6,24,"primary")
        b.text(c,lab,cx0+44,cy0,size=11,color="text2",w=108)
        b.text(c,val,cx0+44,cy0+16,size=16,weight="Bold",w=108)
    bt=card(b,f,"cartao-bateria",16,572,328,56)
    b.icon(bt,"battery",14,16,24,"success")
    b.text(bt,"Bateria na viagem",50,0,size=13,color="text2",w=140,h=56)
    b.text(bt,"82% → 76%",196,0,size=16,weight="Bold",w=118,align="right",h=56)
    b.text(f,"Trajetos e viagens ficam salvos só no seu celular.",16,650,size=12,color="text2",w=328,align="center")
    return f

def menu_popup(b,f,x,y,items):
    """items: [(rótulo, ref_key)] ; retorna refs de cada item."""
    m=b.frame("menu-popup",x,y,196,12+44*len(items),parent=f,fill="card",stroke="stroke",radius=14,
              effects=[{"type":"dropShadow","color":"#00000066","offset":[0,6],"radius":18}])
    out={}
    for i,(lab,key) in enumerate(items):
        it=b.frame("item-"+key,6,6+44*i,184,44,parent=m)
        b.text(it,lab,12,0,size=15,w=170,h=44,color="error" if key=="zerar" and False else "text")
        out[key]=it
    return out

def dialogo_zerar(b,f):
    sc=b.frame("scrim",0,0,W,TH_,parent=f,fill="scrim"); b.add("node.set",{"id":sc,"opacity":0.6})
    d=b.frame("dialogo",28,260,304,196,parent=f,fill="card",stroke="stroke",radius=24)
    b.text(d,"Zerar a viagem atual?",20,20,size=18,weight="Bold",w=264)
    b.text(d,"Ela será salva no histórico e o contador recomeça do zero. Nada é enviado ao patinete.",20,54,size=13,color="text2",w=264)
    can=b.frame("cancelar",20,122,128,52,parent=d,fill="track",radius=16); b.text(can,"Cancelar",0,0,size=15,weight="SemiBold",w=128,align="center",h=52)
    ok=b.frame("zerar",156,122,128,52,parent=d,fill="primary",radius=16); b.text(ok,"Zerar",0,0,size=15,weight="Bold",color="on-primary",w=128,align="center",h=52)
    return {"cancelar":can,"zerar":ok}
TH_=900


def historico_excluir(b,x,y,name):
    """Histórico com o diálogo "Excluir esta viagem?" (viagem 0 da lista)."""
    f=historico(b,x,y,name)
    sc=b.frame("scrim",0,0,W,TH_,parent=f,fill="scrim"); b.add("node.set",{"id":sc,"opacity":0.6})
    d=b.frame("dialogo",28,260,304,216,parent=f,fill="card",stroke="stroke",radius=24)
    b.text(d,"Excluir esta viagem?",20,20,size=18,weight="Bold",w=264)
    b.text(d,"Hoje, 18:42 · 4,8 km · 21 min. O trajeto no mapa também será apagado. Isso não pode ser desfeito.",20,54,size=13,color="text2",w=264)
    can=b.frame("cancelar",20,142,128,52,parent=d,fill="track",radius=16); b.text(can,"Cancelar",0,0,size=15,weight="SemiBold",w=128,align="center",h=52)
    ok=b.frame("excluir",156,142,128,52,parent=d,fill="error",radius=16); b.text(ok,"Excluir",0,0,size=15,weight="Bold",color="on-error",w=128,align="center",h=52)
    b.refs[f"{name}:cancelar"]=can; b.refs[f"{name}:excluir"]=ok
    return f
