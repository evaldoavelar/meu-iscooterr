from lib import *
from home import card, toggle, segmented, home
from history import header, TH_

def botao(b,f,txt,estilo="primary",icon=None,y=702):
    bg,fg={"primary":("primary","on-primary"),"tonal":("track","text"),"disabled":("disabled","on-disabled"),"error":("error","on-color")}[estilo]
    btn=b.frame("botao-principal",16,y,328,60,parent=f,fill=bg,radius=20)
    b.text(btn,txt,0,0,size=19,weight="Bold",color=fg,w=328,align="center",h=60)
    return btn

TITULO_W={"Bloquear ao desconectar":178}
def linha(b,p,y,titulo,sub=None,tog=None,cor="text",chev=False,valor=None,badge=None,tog_y=None,sub_w=230,sub_dy=30,tit_h=None):
    t=b.text(p,titulo,16,y+(8 if sub else 0),size=15,weight="SemiBold" if cor!="text" else "Regular",color=cor,w=250 if not badge else None,h=tit_h or (22 if sub else 56))
    if badge: b.text(p,badge,16+TITULO_W.get(titulo,160)+8,y+10,size=11,color="text2")  # largura do título medida no FigCraft (15px Regular)
    if sub: b.text(p,sub,16,y+sub_dy,size=12,color="text2",w=sub_w)
    if tog is not None: toggle(b,p,266,y+(tog_y if tog_y is not None else 16),tog)
    if chev: b.icon(p,"chev",292,y+16,24,"text2")
    if valor: b.text(p,valor,200,y,size=14,color="text2",w=100,align="right",h=56)

def configuracoes(b,x,y,name):
    # posições medidas no app real (ScreensScreenshotTest, dp): cabeçalho mais alto, cards 99dp, linha de viagem com título em 2 linhas
    f=b.frame(name,x,y,W,H,fill="bg",clip=True)
    back=b.frame("voltar",16,0,48,48,parent=f); b.icon(back,"back",12,12,24,"text"); b.refs[name+":voltar"]=back
    b.text(f,"Preferências",56,4,size=20,weight="Bold",w=W-112,align="center",h=40)
    c=card(b,f,"aparencia",16,58,328,99); b.text(c,"Tema",16,14,size=13,color="text2",w=200); segmented(b,c,14,38,300,48,["Sistema","Claro","Escuro"],0)
    c=card(b,f,"unidades",16,168,328,99); b.text(c,"Unidade de distância e velocidade",16,14,size=13,color="text2",w=280); segmented(b,c,14,38,300,48,["km e km/h","mi e mph"],0)
    c=card(b,f,"preferencias",16,278,328,315); linha(b,c,0,"Reconectar automaticamente","Quando o patinete for ligado",True,sub_dy=33)
    b.frame("div",16,56,296,1,parent=c,fill="stroke"); linha(b,c,57,"Guardar trajeto das viagens","Só neste celular, usa o GPS",True,tog_y=14,sub_dy=33)
    b.frame("div2",16,113,296,1,parent=c,fill="stroke")
    linha(b,c,114,"Iniciar viagem ao abrir a\nTelemetria","A cada abertura, se parada. Desligado: abre parada até tocar Play",True,tog_y=36,sub_w=245,sub_dy=57,tit_h=48)
    b.frame("div3",16,210,296,1,parent=c,fill="stroke")
    linha(b,c,211,"Bloquear ao desconectar","Marcado: o patinete deve bloquear sozinho quando o app desconectar e desbloquear ao reconectar. Desmarcado: não deve bloquear ao desconectar.",False,badge="Em teste",tog_y=40,sub_w=245,sub_dy=33)
    c=card(b,f,"patinete",16,603,328,169); linha(b,c,0,"iX7 Pro")
    b.frame("div",16,56,296,1,parent=c,fill="stroke"); linha(b,c,57,"Esquecer este patinete",cor="error")
    b.frame("div2",16,113,296,1,parent=c,fill="stroke"); linha(b,c,114,"Apagar histórico de viagens",cor="error")
    c=card(b,f,"sobre",16,783,328,113); linha(b,c,0,"Versão 0.1 (protótipo)"); b.frame("div",16,56,296,1,parent=c,fill="stroke"); linha(b,c,57,"Código aberto · licença MIT")
    return f

def status(b,x,y,name,icone,cor,titulo,texto,btn,estilo="primary",extra=None,spin=False):
    f=b.frame(name,x,y,W,H,fill="bg",clip=True)
    c=b.frame("selo",110,190,140,140,parent=f,fill="card",stroke=cor,sw=3,radius=70)
    b.icon(c,icone,38,38,64,cor,sw=3)
    b.text(f,titulo,24,356,size=24,weight="Bold",w=312,align="center")
    b.text(f,texto,32,400,size=15,color="text2",w=296,align="center")
    b.refs[name+":botao"]=botao(b,f,btn,estilo)
    if extra: b.refs[name+":extra"]=botao(b,f,extra,"tonal",y=626)
    return f

def primeiro_uso(b,x,y,name,etapa):
    f=b.frame(name,x,y,W,H,fill="bg",clip=True)
    b.text(f,"Vamos conectar seu patinete",24,64,size=26,weight="Bold",w=312)
    b.text(f,"Isso é feito só uma vez." if etapa<2 else "Tudo certo!",24,134,size=15,color="text2",w=312)
    if etapa==0:
        for k,(rr,al) in enumerate([(120,0.15),(90,0.3),(60,0.6)]):
            r=b.frame("onda",180-rr,300-rr,2*rr,2*rr,parent=f,stroke="primary",sw=2,radius=rr,fill=None); b.add("node.set",{"id":r,"opacity":al})
        d=b.frame("bt",150,270,60,60,parent=f,fill="primary",radius=30); b.icon(d,"bluetooth",14,14,32,"on-primary",sw=2.5)
        b.text(f,"Procurando seu patinete…",24,450,size=18,weight="SemiBold",w=312,align="center")
        for k,t in enumerate(["1. Ligue o patinete","2. Fique perto dele (1 a 2 metros)","3. Deixe o Bluetooth do celular ligado"]):
            b.text(f,t,56,500+k*30,size=14,color="text2",w=280)
        b.refs[name+":botao"]=botao(b,f,"Cancelar","tonal")
    elif etapa==1:
        b.text(f,"Encontramos este patinete",24,190,size=15,weight="SemiBold",w=312)
        c=card(b,f,"achado",16,222,328,84); b.refs[name+":achado"]=c
        t=b.frame("ic",16,22,40,40,parent=c,fill="track",radius=12); b.icon(t,"scooter",7,7,26,"primary")
        b.text(c,"iX7 Pro",70,16,size=18,weight="Bold",w=180); b.text(c,"Sinal forte · bem perto",70,44,size=13,color="text2",w=200)
        b.frame("dot",290,38,10,10,parent=c,fill="success",radius=5)
        b.text(f,"É o seu? Confirme antes de conectar. O app só controla patinetes que você escolher.",24,326,size=13,color="text2",w=312)
        b.refs[name+":botao"]=botao(b,f,"Conectar a este patinete")
    else:
        c=b.frame("selo",110,200,140,140,parent=f,fill="card",stroke="success",sw=3,radius=70); b.icon(c,"check",38,38,64,"success",sw=3.5)
        b.text(f,"iX7 Pro conectado",24,366,size=24,weight="Bold",w=312,align="center")
        b.text(f,"Agora é só usar o app.",32,410,size=15,color="text2",w=296,align="center")
        b.refs[name+":botao"]=botao(b,f,"Começar")
    return f
