from lib import *

def card(b,p,name,x,y,w,h,state="ok"):
    stroke={"ok":"stroke","pending":"pending"}[state]
    return b.frame(name,x,y,w,h,parent=p,fill="card",stroke=stroke,radius=16,sw=2 if state=="pending" else 1)

def toggle(b,p,x,y,on,state="ok"):
    col = "pending" if state=="pending" else ("primary" if on else "track")
    t=b.frame("toggle",x,y,52,30,parent=p,fill=col,radius=15)
    kx = x+0 + (25 if on else 3)
    b.frame("thumb",kx-x,3,24,24,parent=t,fill="text" if on or state=="pending" else "text2",radius=12)
    return t

def segmented(b,p,x,y,w,h,labels,sel,state="ok"):
    s=b.frame("segmented",x,y,w,h,parent=p,fill="track",radius=h//2)
    sw=(w-8)/len(labels)
    for i,l in enumerate(labels):
        on = i==sel
        if on:
            b.frame("seg-sel",4+i*sw,4,sw,h-8,parent=s,fill="pending" if state=="pending" else "primary",radius=(h-8)//2)
        b.text(s,l,4+i*sw,(h-18)//2-1,size=14,color="on-color" if on else "text",w=sw,weight="SemiBold" if on else "Regular",align="center",name="seg-"+l)
    return s

def home(b,x,y,name,conn="conectado",lock="locked",pending=None,moving=False,confirmed=False,fail=False):
    """conn: conectado|conectando|desconectado; lock: locked|unlocked; pending: None|'lock'|'farol'|'tracao'|'modo'|'cruise'|'partida'|'limite'"""
    f=b.frame(name,x,y,W,H,fill="bg",radius=0,clip=True)
    online = conn=="conectado"
    # header
    b.text(f,APP_NAME,16,28,size=24,weight="Bold",w=260)
    b.icon(f,"gear",320-4,28,24,"text")
    # chip
    chip=b.frame("chip-conexao",16,70,328,36,parent=f,fill="card",stroke="stroke",radius=18)
    b.icon(chip,"bluetooth",12,6,24,"primary" if online else "text2")
    label={"conectado":"Conectado · iX7 Pro","conectando":"Conectando ao iX7 Pro…","desconectado":"Desconectado · toque para procurar"}[conn]
    if fail: label="Sem resposta do patinete"
    b.text(chip,label,48,8,size=15,w=240)
    dot="error" if fail else {"conectado":"success","conectando":"pending","desconectado":"error"}[conn]
    b.frame("dot",300,13,10,10,parent=chip,fill=dot,radius=5)
    # info cards
    vals=[("battery","82%","Bateria","success"),("speed","12 km/h" if moving else "0 km/h","Velocidade","primary"),("pin","1.248 km","Total","primary")]
    if not online: vals=[(i,"--",l,c) for i,_,l,c in vals]
    for i,(ic,v,l,c) in enumerate(vals):
        cx=16+i*112
        c0=card(b,f,"info-"+l,cx,118,104,78)
        b.icon(c0,ic,10,10,22,c)
        if i==2 and online: b.icon(c0,"chev",76,10,18,"text2")
        b.text(c0,v,10,34,size=17,weight="Bold",w=90)
        b.text(c0,l,10,56,size=12,color="text2",w=90)
    # controls
    def st(k): return "pending" if pending==k else "ok"
    off = not online
    # farol
    r=card(b,f,"linha-farol",16,206,328,60,st("farol"))
    b.icon(r,"light",16,18,24,"pending" if pending=="farol" else "primary")
    b.text(r,"Farol",60,17,size=17,weight="SemiBold",w=160)
    toggle(b,r,262,15,on=True,state=st("farol"))
    # tração
    r=card(b,f,"linha-tracao",16,274,328,72,st("tracao"))
    b.icon(r,"motor",16,24,24,"pending" if pending=="tracao" else "primary")
    b.text(r,"Tração",60,12,size=17,weight="SemiBold",w=110)
    b.text(r,"Acionamento do motor",60,34,size=12,color="text2",w=90)
    segmented(b,r,150,16,166,40,["1 motor","2 motores"],0 if pending!="tracao" else 1,st("tracao"))
    # modo
    r=card(b,f,"linha-modo",16,354,328,96,st("modo"))
    b.icon(r,"speed",16,10,24,"pending" if pending=="modo" else "primary")
    b.text(r,"Modo de velocidade",60,8,size=17,weight="SemiBold",w=250)
    b.text(r,"Ajusta a resposta e a velocidade máxima",60,30,size=12,color="text2",w=260)
    segmented(b,r,12,46,304,40,["Eco","Drive","Sport"],1 if pending!="modo" else 2,st("modo"))
    # cruise / partida
    for j,(k,ic,t,sub,on) in enumerate([("cruise","cruise","Cruise","Mantém a velocidade constante",False),("partida","rocket","Partida do zero","Aceleração sem empurrar",True)]):
        r=card(b,f,"cartao-"+k,16+j*168,458,160,108,st(k))
        b.icon(r,ic,12,12,22,"pending" if pending==k else "primary")
        b.text(r,t,40,13,size=14,weight="SemiBold",w=116)
        b.text(r,sub,12,40,size=12,color="text2",w=136)
        toggle(b,r,12,70,on if pending!=k else (not on),st(k))
    # limite
    r=card(b,f,"cartao-limite",16,574,328,100,st("limite"))
    b.icon(r,"speed",16,12,22,"pending" if pending=="limite" else "primary")
    b.text(r,"Limite de velocidade",48,12,size=15,weight="SemiBold",w=190)
    b.text(r,"25 km/h",232,10,size=19,color="pending" if pending=="limite" else "primary",weight="Bold",w=84,align="right")
    b.frame("slider-trilho",16,56,296,6,parent=r,fill="track",radius=3)
    b.frame("slider-valor",16,56,296*0.17,6,parent=r,fill="pending" if pending=="limite" else "primary",radius=3)
    b.frame("slider-thumb",16+296*0.17-14,45,28,28,parent=r,fill="pending" if pending=="limite" else "primary",stroke="bg",radius=14,sw=3)
    b.text(r,"20 km/h",16,74,size=12,color="text2",w=80)
    b.text(r,"50 km/h",232,74,size=12,color="text2",w=80,align="right")
    # botão rodapé
    if not online and conn=="desconectado": bg,fg,ic,txt="disabled","on-disabled","unlock","Procurar patinete"
    elif conn=="conectando": bg,fg,ic,txt="disabled","on-disabled","spin","Conectando…"
    elif fail: bg,fg,ic,txt="error","on-color","alert","Tentar de novo"
    elif pending=="lock": bg,fg,ic,txt="pending","on-color","spin","Aguardando o patinete…"
    elif confirmed: bg,fg,ic,txt="success","on-color","check",("Bloqueado às 14:32" if lock=="locked" else "Desbloqueado às 14:32")
    elif lock=="locked": bg,fg,ic,txt="primary","on-primary","unlock","Desbloquear"
    elif moving: bg,fg,ic,txt="disabled","on-disabled","lock","Pare para bloquear"
    else: bg,fg,ic,txt="card","primary","lock","Bloquear"
    btn=b.frame("botao-principal",16,694,328,64,parent=f,fill=bg,stroke="primary" if (bg=="card") else None,sw=2,radius=20,
        effects=[{"type":"dropShadow","color":"#00E5FF55","offset":[0,0],"radius":18}] if bg=="primary" else None)
    fs=22 if len(txt)<20 else 19; tw=len(txt)*fs*0.555; x0=(328-(32+14+tw))/2
    b.icon(btn,ic,x0,16,32,fg,sw=2.5)
    b.text(btn,txt,x0+46,17,size=fs,color=fg,weight="Bold",w=tw+16,name="botao-rotulo")
    return f
