"""S52: propostas (só mockups, dados de exemplo) — (1) fundo de mapa desfocado nos cartões de trajeto do Histórico,
variantes A (miniatura 72 dp com mapa desfocado atrás do trajeto) e B (cartão inteiro com mapa desfocado + véu na cor do cartão);
(2) aviso de Economia de energia ligada durante a gravação da viagem (Telemetria).
Só tokens/ícones existentes de lib.py; reaproveita mapa/route de history.py e borrado/cab/cartao de s45.py."""
from lib import *
from home import card
from history import mapa, route
from telemetry import telemetria
from s43 import S, T, th
from s45 import borrado, cab, legenda_quadro
from screens import botao, linha, status

FR=[]
Y0=70000

def hexf(b,r,color):
    b.add("node.set",{"id":r,"fill":color})

def osm(b,p,x,y,w,h,seed,radius,blur):
    """mapa estilo OSM (tiles claros, como no app: o mapa é claro mesmo no tema escuro), desfocado; a moldura recorta as bordas"""
    import random
    wr=b.frame("mapa-osm-desfocado",x,y,w,h,parent=p,radius=radius,clip=True); hexf(b,wr,"#EDE8DF")
    pad=16; g=b.frame("camada",-pad,-pad,w+2*pad,h+2*pad,parent=wr); hexf(b,g,"#EDE8DF")
    r=random.Random(seed); W_,H_=w+2*pad,h+2*pad
    for _ in range(7):  # quarteirões, parque, água
        q=b.frame("bloco",r.uniform(0,W_*.8),r.uniform(0,H_*.8),r.uniform(18,48),r.uniform(14,36),parent=g,radius=3)
        hexf(b,q,r.choice(("#E3DDD0","#D9E8C8","#E8E1D3","#DCE6D0")))
    ag=b.frame("agua",r.uniform(0,W_*.5),r.uniform(0,H_*.5),W_*.35,H_*.22,parent=g,radius=8); hexf(b,ag,"#AFCBE8")
    for fy in (0.18,0.42,0.66,0.86):
        ro=b.frame("rua-h",0,H_*fy,W_,5,parent=g); hexf(b,ro,"#FFFFFF")
    for fx in (0.14,0.38,0.6,0.82):
        ro=b.frame("rua-v",W_*fx,0,5,H_,parent=g); hexf(b,ro,"#F6C95B" if fx==0.38 else "#FFFFFF")
    b.add("node.set",{"id":g,"effects":[{"type":"layerBlur","radius":blur}]})
    return wr

def trajeto(b,p,w,h,seed,thumb=True):
    """trajeto nítido (ciano + início/fim) desenhado SOBRE um fundo já desfocado"""
    pts=route(seed,w,h)
    d="M"+" L".join(f"{px:.1f} {py:.1f}" for px,py in pts)
    c0=b.uid("rc"); b.add("vector.create",{"path":d,"x":0,"y":0,"parent":p,"name":"trajeto-contorno","select":False},c0)
    b.add("node.set",{"id":{"$ref":c0},"fill":"none","stroke":"#0F1A2A","strokeWeight":6,"strokeCap":"round","strokeJoin":"round"})
    a=b.uid("rt"); b.add("vector.create",{"path":d,"x":0,"y":0,"parent":p,"name":"trajeto","select":False},a)
    b.add("node.set",{"id":{"$ref":a},"fill":"none","stroke":{"$var":"primary"},"strokeWeight":3,"strokeCap":"round","strokeJoin":"round",
                      "effects":[]})
    s=5
    b.frame("inicio",pts[0][0]-s,pts[0][1]-s,2*s,2*s,parent=p,fill="success",stroke="card",radius=s,sw=2)
    b.frame("fim",pts[-1][0]-s,pts[-1][1]-s,2*s,2*s,parent=p,fill="primary",stroke="card",radius=s,sw=2)

def mini_pino(b,c):
    wr=osm(b,c,8,8,72,72,23,14,4)
    pn=b.frame("pino",22,22,28,28,parent=wr,fill="card",stroke="stroke",radius=14)
    b.icon(pn,"pin",5,5,18,"text2")

ITENS=(("Hoje, 07:57","0,6 km · 5 min","média 7 km/h · máx 26 km/h",11,"rota"),
       ("Sáb 14, 10:08","1,5 km · 13 min","média 7 km/h · máx 30 km/h",37,"rota"),
       ("Qui 12, 17:44","1,0 km · 7 min","média 8 km/h · máx 30 km/h",41,"rota"),
       ("Qui 12, 12:58","0,0 km · 8 min","média 0 km/h · máx 0 km/h",23,"mapa"))

def cartao_a(b,f,y,t,l2,l3,seed,kind):
    """A: miniatura com mapa desfocado atrás do trajeto; o resto do cartão como hoje"""
    c=card(b,f,"viagem",16,y,328,88)
    if kind=="rota":
        wr=osm(b,c,8,8,72,72,seed,14,3)
        trajeto(b,wr,72,72,seed)
    else: mini_pino(b,c)
    b.text(c,t,92,10,size=15,weight="SemiBold",w=200)
    b.text(c,l2,92,34,size=14,weight="Bold",w=200,color="primary")
    b.text(c,l3,92,58,size=12,color="text2",w=200)
    b.icon(c,"chev",296,32,24,"text2")

def cartao_b(b,f,y,t,l2,l3,seed,kind):
    """B: cartão inteiro com o mapa desfocado ao fundo + véu na cor do cartão (texto mantém o contraste do tema)"""
    c=card(b,f,"viagem",16,y,328,88); b.add("node.set",{"id":c,"clip":True})
    osm(b,c,0,0,328,88,seed,16,4)
    v=b.frame("veu-cartao",0,0,328,88,parent=c,fill="card"); b.add("node.set",{"id":v,"opacity":0.82})
    if kind=="rota":
        g=b.frame("trajeto-area",8,8,72,72,parent=c); trajeto(b,g,72,72,seed)
    else:
        pn=b.frame("pino",30,30,28,28,parent=c,fill="card",stroke="stroke",radius=14); b.icon(pn,"pin",5,5,18,"text2")
    b.text(c,t,92,10,size=15,weight="SemiBold",w=200)
    b.text(c,l2,92,34,size=14,weight="Bold",w=200,color="primary")
    b.text(c,l3,92,58,size=12,color="text2",w=200)
    b.icon(c,"chev",296,32,24,"text2")
    b.frame("borda",0,0,328,88,parent=c,stroke="stroke",radius=16)

def lista(b,x,y,name,var):
    f=b.frame(name,x,y,W,100,fill="bg",clip=True); FR.append(f)
    cab(b,f,1,"Histórico")
    s=card(b,f,"resumo",16,72,328,72)
    for j,(v,lab) in enumerate([("6","Viagens"),("3,1 km","Distância"),("37 min","Tempo")]):
        b.text(s,v,14+j*108,14,size=18,weight="Bold",w=100); b.text(s,lab,14+j*108,42,size=11,color="text2",w=104)
    cur=158
    fn=cartao_a if var=="A" else cartao_b
    b.text(f,"Hoje",20,cur,size=13,weight="SemiBold",color="text2",w=200); cur+=26
    for i,(t,l2,l3,seed,kind) in enumerate(ITENS):
        if i==1: b.text(f,"Semana passada",20,cur,size=13,weight="SemiBold",color="text2",w=200); cur+=26
        fn(b,f,cur,t,l2,l3,seed,kind); cur+=98
    cap=("A: o fundo azul liso da miniatura vira o mapa (claro, como no app) desfocado; o trajeto continua nítido por cima. O cartão não muda."
         if var=="A" else "B: o cartão inteiro ganha o mapa desfocado ao fundo, com véu na cor do cartão (82 %) para manter o contraste do texto.")
    cap+=" Mapa esquemático de exemplo; no app seria o mapa OSM já em cache da própria viagem (sem rede extra); sem mapa disponível, volta ao fundo atual."
    cur=legenda_quadro(b,f,cur+4,1,cap)
    b.add("node.set",{"id":f,"height":max(cur,H)}); return max(cur,H)

def aviso(b,x,y,name):
    f=telemetria(b,x,y,name,fixed=True,speed=18,app=True,trip="run",aviso=True); FR.append(f)
    b.text(f,"Aviso compacto entre a bateria e a Duração (rola junto). Toque ou toque longo no cartão abre os ajustes de bateria. Só com viagem gravando e Economia de energia ligada.",16,H+8,size=11,color="text2",w=328)
    return f

# ------------------------------------------------------------------ Backup / Restaurar (S52)
Y1=80000

def campo(b,p,x,y,w,rot,dots,erro=False):
    b.text(p,rot,x,y,size=12,color="text2",w=w)
    c=b.frame("campo",x,y+20,w,52,parent=p,fill="bg",stroke="error" if erro else "stroke",radius=12,sw=1)
    b.text(c,dots,16,0,size=20,weight="Bold",w=w-60,h=52); b.text(c,"mostrar",w-72,0,size=12,color="primary",w=60,align="right",h=52)
    return c

def item(b,p,y,icone,tit,sub):
    t=b.frame("ic-tile",14,y+10,36,36,parent=p,fill="track",radius=10); b.icon(t,icone,6,6,24,"primary")
    b.text(p,tit,62,y+8,size=14,weight="SemiBold",w=250); b.text(p,sub,62,y+28,size=12,color="text2",w=250)
    b.icon(p,"check",292,y+16,24,"success")

def prefs_sistema(b,x,y,name):
    f=b.frame(name,x,y,W,H,fill="bg",clip=True); FR.append(f)
    cab(b,f,1,"Preferências")
    c=card(b,f,"patinete",16,72,328,113); linha(b,c,0,"Esquecer este patinete",cor="error"); b.frame("div",16,56,296,1,parent=c,fill="stroke"); linha(b,c,57,"Apagar histórico de viagens",cor="error")
    b.text(f,"Sistema",20,202,size=13,weight="SemiBold",color="text2",w=200)
    c=card(b,f,"sistema",16,228,328,129)
    b.text(c,"Fazer backup",16,10,size=15,w=250); b.text(c,"Salva viagens, patinete, PIN e preferências em um arquivo",16,34,size=12,color="text2",w=250); b.icon(c,"chev",292,16,24,"text2")
    b.frame("div",16,64,296,1,parent=c,fill="stroke")
    b.text(c,"Restaurar backup",16,74,size=15,w=250); b.text(c,"Recupera tudo a partir de um arquivo de backup",16,98,size=12,color="text2",w=250); b.icon(c,"chev",292,80,24,"text2")
    c=card(b,f,"sobre",16,372,328,113); linha(b,c,0,"Versão 0.1 (protótipo)"); b.frame("div",16,56,296,1,parent=c,fill="stroke"); linha(b,c,57,"Código aberto · licença MIT")
    T(b,f,"Nova seção “Sistema” em Preferências, acima de “Sobre” (rolada até o fim; o topo da tela não muda).",16,500,11,1,w=328,color="text2")
    return f

def backup_criar(b,x,y,name):
    f=b.frame(name,x,y,W,H,fill="bg",clip=True); FR.append(f)
    cab(b,f,1,"Fazer backup")
    T(b,f,"Salva um arquivo para recuperar tudo ao reinstalar o app ou trocar de celular.",20,68,14,1,w=320,color="text2")
    c=card(b,f,"conteudo",16,116,328,252)
    b.text(c,"O que vai no backup",16,12,size=13,color="text2",w=250)
    item(b,c,34,"route","Histórico de viagens","6 viagens e seus trajetos")
    item(b,c,84,"scooter","Patinete","iX7 Pro (endereço Bluetooth)")
    item(b,c,134,"lock","PIN de desbloqueio","Guardado cifrado com a senha abaixo")
    item(b,c,184,"gear","Preferências","Tema, unidades e opções")
    c=card(b,f,"senha",16,380,328,214)
    b.text(c,"Senha do backup",16,12,size=13,color="text2",w=250)
    campo(b,c,16,34,296,"Senha","••••••••••"); campo(b,c,16,108,296,"Repetir senha","••••••••••")
    T(b,f,"Mínimo de 8 caracteres. O PIN vai cifrado com esta senha. Sem ela o backup não abre e não há como recuperá-la.",20,604,12,1,w=320,color="text2")
    botao(b,f,"Salvar backup…","primary",y=668)
    T(b,f,"O Android abre o seletor de arquivos para você escolher onde salvar (celular, Drive…). Ao tocar, o app pede sua digital para ler o PIN do cofre.",20,732,11,1,w=320,color="text2")
    return f

def restaurar(b,x,y,name):
    f=b.frame(name,x,y,W,H,fill="bg",clip=True); FR.append(f)
    cab(b,f,1,"Restaurar backup")
    c=card(b,f,"arquivo",16,72,328,72)
    t=b.frame("ic-tile",14,18,36,36,parent=c,fill="track",radius=10); b.icon(t,"check",6,6,24,"success")
    b.text(c,"meu-iscooter-2026-11-17.ixbak",62,14,size=14,weight="SemiBold",w=250); b.text(c,"38 KB · criado em 17/11/2026, 08:40",62,38,size=12,color="text2",w=250)
    c=b.frame("senha-bloco",16,158,328,92,parent=f); campo(b,c,0,0,328,"Senha do backup","••••••••••")
    c=card(b,f,"conteudo",16,262,328,252)
    b.text(c,"Dentro do backup",16,12,size=13,color="text2",w=250)
    item(b,c,34,"route","Histórico de viagens","6 viagens e seus trajetos")
    item(b,c,84,"scooter","Patinete","iX7 Pro")
    item(b,c,134,"lock","PIN de desbloqueio","Será guardado de novo no cofre")
    item(b,c,184,"gear","Preferências","Tema, unidades e opções")
    w=b.frame("aviso",16,528,328,92,parent=f,fill="card",stroke="pending",radius=16,sw=1)
    t=b.frame("ic-tile",12,12,36,36,parent=w,fill="track",radius=10); b.icon(t,"alert",6,6,24,"pending")
    b.text(w,"Substitui o que há neste celular",58,12,size=14,weight="SemiBold",w=258)
    b.text(w,"Histórico, patinete e PIN atuais serão trocados pelos do backup.",58,34,size=12,color="text2",w=258,h=44)
    botao(b,f,"Restaurar","primary",y=668)
    T(b,f,"Depois de restaurar, o app pede sua digital para guardar o PIN no cofre deste celular.",20,732,11,1,w=320,color="text2")
    return f

def s52_backup(b,setm):
    y=Y1; names=[]; x=0
    for nm,fn in (("preferencias-sistema-backup-v1",lambda n:prefs_sistema(b,x,y,n)),
                  ("backup-criar-v1",lambda n:backup_criar(b,x,y,n)),
                  ("backup-concluido-v1",lambda n:FR.append(status(b,x,y,n,"check","success","Backup salvo","meu-iscooter-2026-11-17.ixbak · 38 KB. Guarde a senha: sem ela não dá para restaurar.","Concluir"))),
                  ("restaurar-v1",lambda n:restaurar(b,x,y,n)),
                  ("restaurar-concluido-v1",lambda n:FR.append(status(b,x,y,n,"check","success","Tudo restaurado","6 viagens, o iX7 Pro e o PIN foram recuperados. Pode conectar ao patinete.","Abrir o app"))),
                  ("primeira-abertura-backup-v1",lambda n:FR.append(status(b,x,y,n,"scooter","primary","Já usou o app antes?","Se você tem um backup, recupere viagens, patinete e PIN. Senão, comece do zero.","Restaurar backup","primary","Começar do zero")))):
        fn(nm); names.append(nm); x+=400
    for f in FR: setm(f)
    FR.clear(); return names

def s52_frames(b,setm):
    y=Y0; names=[]; x=0
    for var in ("A","B"):
        nm=f"historico-fundo-mapa-{var}-v1"; lista(b,x,y,nm,var); names.append(nm); x+=400
    nm="telemetria-aviso-economia-v1"; aviso(b,x,y,nm); names.append(nm)
    for f in FR: setm(f)
    FR.clear(); names+=s52_backup(b,setm); return names
