import math
from lib import *
from home import card
from s43 import S

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

def toast_pill(b,p,txt,fs):
    """Toast nativo (LENGTH_SHORT): pílula escura, texto claro, centrada, base 16 dp acima da barra de ações (topo da barra = 690)."""
    sz=S(fs,14); base=690-16
    if fs<=1: w=212; lines=1
    else: w=320; lines=2
    h=math.ceil(sz*1.25)*lines+24
    tw=w-48
    f=b.frame("toast",(W-w)/2,base-h,w,h,parent=p,fill="toast",stroke="stroke",radius=h/2 if lines==1 else 28,
              effects=[{"type":"dropShadow","color":"#00000066","offset":[0,3],"radius":8}])
    b.text(f,txt,24,12,size=sz,color="on-toast",w=tw,align="center",h=math.ceil(sz*1.25)*lines)
    return f

TH=1180  # tela rolável: altura maior que a do aparelho

def telemetria(b,x,y,name,speed=0,motor2=True,bars=None,overlay=None,trip=None,fixed=False,src="patinete",parar_off=False,fs=1,app=False,nocon=False,toast=None,parent=None):
    # S47-offline: app=True = tela inteira como no app (sem chip, rolagem 0, aviso "Dados desatualizados" no topo); nocon = sem telemetria fresca; toast = texto do Toast
    trip=trip or ("run" if speed>0 else "idle"); has=trip!="idle"; d=156
    if fixed:
        d=86; outer=b.frame(name,x,y,W,H,parent=parent,fill="bg",clip=True); SC=0 if app else 250
        f=b.frame("conteudo",0,-SC,W,TH,parent=outer)
    else: f=b.frame(name,x,y,W,TH,fill="bg",clip=True)
    def header(f):
        # topo
        back=b.frame("voltar",8,16,48,48,parent=f); b.refs[name+":voltar"]=back
        b.icon(back,"back",12,12,24,"text")
        b.text(f,"Telemetria",0,20,size=20,weight="Bold",w=W,align="center",h=40)
        mn=b.frame("menu",W-56,16,48,48,parent=f); b.refs[name+":menu"]=mn
        for k in range(3): b.frame("ponto",22,15+k*7,4,4,parent=mn,fill="text2",radius=2)
    if not fixed: header(f)
    # dispositivo
    if not app: chip=b.frame("chip-conexao",16,70,328,36,parent=f,fill="card",stroke="stroke",radius=18)
    if not app:
        b.icon(chip,"bluetooth",12,6,24,"primary")
        b.text(chip,"Conectado · iX7 Pro",48,0,size=15,w=240,h=36)
        b.frame("dot",300,13,10,10,parent=chip,fill="success",radius=5)
    if nocon: b.text(f,"Dados desatualizados",16,74,size=S(fs,14),color="text2",w=328,align="center",h=24*(2 if fs>1 else 1))
    # velocímetro
    cx,cy,r=180,246,106
    a1=b.uid("arc"); a2=b.uid("arc")
    b.add("vector.create",{"path":arc_path(cx,cy,r,210,-30),"x":0,"y":0,"parent":f,"name":"arco-trilho","select":False},a1)
    b.add("node.set",{"id":{"$ref":a1},"fill":"none","stroke":{"$var":"track"},"strokeWeight":14,"dashes":[5,4],"strokeCap":"none"})
    frac=min(speed/60,1)
    if frac>0:
        b.add("vector.create",{"path":arc_path(cx,cy,r,210,210-240*frac),"x":0,"y":0,"parent":f,"name":"arco-velocidade","select":False},a2)
        b.add("node.set",{"id":{"$ref":a2},"fill":"none","stroke":{"$var":"primary"},"strokeWeight":14,"dashes":[5,4],"strokeCap":"none",
                          "effects":[{"type":"dropShadow","color":"#00E5FF66","offset":[0,0],"radius":14}]})
    # neon externo (contínuo): trilho tênue + progresso com brilho
    ro=r+17; a3=b.uid("arc"); a4=b.uid("arc")
    b.add("vector.create",{"path":arc_path(cx,cy,ro,210,-30),"x":0,"y":0,"parent":f,"name":"neon-trilho","select":False},a3)
    b.add("node.set",{"id":{"$ref":a3},"fill":"none","stroke":{"$var":"track"},"strokeWeight":3,"strokeCap":"round"})
    nf=max(frac,0.02)
    b.add("vector.create",{"path":arc_path(cx,cy,ro,210,210-240*nf),"x":0,"y":0,"parent":f,"name":"neon-progresso","select":False},a4)
    b.add("node.set",{"id":{"$ref":a4},"fill":"none","stroke":{"$var":"primary"},"strokeWeight":5,"strokeCap":"round",
                      "effects":[{"type":"dropShadow","color":"#00E5FFAA","offset":[0,0],"radius":16}]})
    b.text(f,"--" if nocon else str(speed),cx-70,cy-62,size=56,weight="Bold",color="text2" if nocon else "text",w=84,align="right",h=70)
    b.text(f,"km/h",cx+18,cy-40,size=18,weight="SemiBold",color="text2",w=60,h=30)
    b.text(f,"Velocidade atual",cx-80,cy+6,size=13,color="text2",w=160,align="center")
    b.text(f,"0",cx-r*0.87-14,cy+r*0.5+2,size=13,color="text2",w=30,align="center")
    b.text(f,"60",cx+r*0.87-14,cy+r*0.5+2,size=13,color="text2",w=30,align="center")
    # indicador de página (2 pontos): 1º = patinete (padrão), 2º = GPS; o ativo em destaque
    for k in (0,1):
        on=(k==0)==(src!="gps")
        b.frame("pagina-%d"%k,cx-11+k*14,cy+r*0.5+33,8,8,parent=f,fill="primary" if on else "track",radius=4)
    b.icon(f,"gps" if src=="gps" else "scooter",cx-20,cy+r*0.5-10,40,"primary",sw=1.6)   # ícone sensível à fonte: patinete (deslizar p/ direita) ou GPS (deslizar p/ esquerda)
    # bateria
    pill=b.frame("pilula-bateria",90,344,180,38,parent=f,fill="card",stroke="primary",radius=19)
    b.icon(pill,"battery",12,7,24,"text2" if nocon else "success")
    b.text(pill,"--" if nocon else "82%",44,0,size=16,weight="Bold",color="text2" if nocon else "text",w=50,h=38)
    b.frame("sep",100,10,1,18,parent=pill,fill="stroke")
    b.text(pill,"Bateria",112,0,size=13,color="text2",w=60,h=38)
    # duração + barra de ações (Reset | Play/Pause | Parar)
    dc=b.frame("cartao-duracao",16,388,328,76 if fixed else 146,parent=f,fill="card",stroke="stroke",radius=16,clip=True)
    dt=b.frame("ic-tile",14,12,40,40,parent=dc,fill="track",radius=12); b.icon(dt,"clock",8,8,24,"primary")
    b.text(dc,"Duração",64,12,size=12,color="text2",w=120)
    b.text(dc,"12:40" if has and not nocon else "00:00",64,26,size=30,weight="Bold",w=200,h=38)
    if trip=="pause": b.text(dc,"Pausada",236,22,size=12,weight="SemiBold",color="pending",w=78,align="right",h=20)
    elif trip=="run":
        big=app and fs>1; cw_=130 if big else 78
        b.text(dc,"Contando",314-cw_,22,size=S(fs,12) if big else 12,weight="SemiBold",color="error" if nocon else "success",w=cw_,align="right",h=20*(2 if big else 1))
    def bar(dc,w,py,ph,fillv="bg"):
        c3=w/3
        pn=b.uid("pn"); b.add("vector.create",{"path":f"M0 12 A1200 1200 0 0 1 {w} 12 L{w} {ph} L0 {ph} Z","x":0,"y":py,"parent":dc,"name":"painel-barra","select":False},pn)
        b.add("node.set",{"id":{"$ref":pn},"fill":{"$var":fillv},"stroke":{"$var":"stroke"},"strokeWeight":1.5,"strokeAlign":"inside"})
        for k in (1,2): b.frame("divisoria",c3*k,py+24,1,48,parent=dc,fill="stroke")
        # S47 v3: espelha TripControlsBar.kt. Célula = Column(min 64 dp, pad vertical 4, Arrangement.Center) dentro da Row
        # (padding topo 12 dp do arco, altura mínima 72 dp) => centro vertical do conjunto em py+48.
        # Reset/Parar: slot 28 dp (viewBox 24). Parar = quadrado 12u+traço 1,5u => tinta 15,75 dp (o protótipo antigo
        # desenhava 24 dp; agora espelha o app). Reset (AppIcons.Reset, traço 2u como no app): SÓ ele diminui, glifo 19 dp, traço 2x19/24 = 1,58 dp, tinta ~15,83 dp (~Parar).
        # Play/Pause: ícone 40 dp, tinta ~19x27,5 (pause) / ~23x27,5 (play), sem rótulo, centro py+48. Rótulo labelMedium 12 sp SemiBold
        # (linha 16 dp); fonte 2,0 segue S() de s43 (12 -> 24, linha 32), conjunto continua centrado em py+48.
        # MEDIDAS-S47 v3: ver resposta da tarefa (tinta w x h e centros medidos no render 2x).
        CY=48; LH=16*(2 if fs>1 else 1); LS=S(fs,12); IC=28
        top=CY-(IC+LH)/2; icy=top+IC/2; ly=top+IC
        RS=19; RSW=2*RS/24; PH=25; PW=20.5; PSW=2.5; SQ=15.75
        def lab(p,t,x,y): b.text(p,t,x,y,size=LS,weight="SemiBold",color="text",w=c3,align="center",h=LH)
        def reset_ic(p,x,y): b.icon(p,"reset",x-RS/2,y-RS/2,RS,"text",sw=RSW)
        if parar_off:   # S45: sem percurso gravando, Reset e Parar desabilitados (alpha 0,38 como no app)
            rs=b.frame("celula-reset",0,py,c3,ph,parent=dc); b.add("node.set",{"id":rs,"opacity":0.38})
            reset_ic(rs,c3/2,icy); lab(rs,"Reset",0,ly)
        else:
            reset_ic(dc,c3/2,py+icy); lab(dc,"Reset",0,py+ly)
        mx=w/2
        if trip=="run":
            for xx in (mx-9.5,mx+2.5): b.frame("pause-barra",xx,py+CY-13.75,7,27.5,parent=dc,fill="primary",radius=2)
        else:
            pl=b.uid("pl"); b.add("vector.create",{"path":f"M0 0 L0 {PH} L{PW} {PH/2} Z","x":mx-PW/2-0.5,"y":py+CY-PH/2,"parent":dc,"name":"play","select":False},pl)
            b.add("node.set",{"id":{"$ref":pl},"fill":{"$var":"primary"},"stroke":{"$var":"primary"},"strokeWeight":PSW,"strokeJoin":"round","effects":[{"type":"dropShadow","color":"#00E5FF55","offset":[0,0],"radius":10}]})
        if parar_off:
            pr=b.frame("celula-parar",2*c3,py,c3,ph,parent=dc); b.add("node.set",{"id":pr,"opacity":0.38})
            b.frame("parar-quadrado",c3/2-SQ/2,icy-SQ/2,SQ,SQ,parent=pr,fill="primary",radius=2.6)
            lab(pr,"Parar",0,ly)
        else:
            b.frame("parar-quadrado",w-c3/2-SQ/2,py+icy-SQ/2,SQ,SQ,parent=dc,fill="primary",radius=2.6)
            lab(dc,"Parar",2*c3,py+ly)
    if not fixed:
        py,ph=66,80
        bar(dc,328,py,ph)
        b.refs[name+":reset"]=dc
    # consumo em tempo real
    amp,volt=(8.4,54.9) if speed>0 else (0.0,55.4)
    if nocon: amp=volt=None
    watt=None if nocon else round(amp*volt)
    c=card(b,f,"cartao-consumo",16,388+d,328,92)
    b.text(c,"Consumo em tempo real",14,10,size=12,color="text2",w=200)
    b.text(c,"-- W" if nocon else f"{watt} W",14,28,size=28,weight="Bold",w=140,h=36,color="text2" if nocon else "text")
    b.text(c,("Sem dado do patinete" if nocon else f"{watt/speed:.1f} Wh/km".replace(".",",") if speed>0 else "Parado · sem consumo"),14,66,size=12,color="text2",w=150)
    bars=bars or ([0]*24 if speed==0 else [30,34,40,52,60,58,48,44,50,62,70,66,58,52,48,56,64,72,68,60,54,50,58,62])
    for i,v in enumerate(bars):
        hh=max(3,v*0.6)
        b.frame("barra",168+i*6.4,70-hh+0,4,hh,parent=c,fill="primary" if speed>0 else "track",radius=2)
    b.text(c,"últimos 30 s",168,74,size=10,color="text2",w=150)
    # corrente / tensão
    stat(b,f,"cartao-corrente",16,490+d,160,64,"pulse","Corrente","--" if nocon else f"{amp:.1f}".replace(".",","),"A")
    stat(b,f,"cartao-tensao",184,490+d,160,64,"bolt","Tensão","--" if nocon else f"{volt:.1f}".replace(".",","),"V")
    # temperaturas
    stat(b,f,"cartao-temp-motor1",16,562+d,160,76,"thermo","Temp. motor 1","22" if speed==0 else "41","°C")
    stat(b,f,"cartao-temp-motor2",184,562+d,160,76,"thermo","Temp. motor 2","--" if not motor2 else ("22" if speed==0 else "39"),"°C" if motor2 else "",sub=None if motor2 else "Sem leitura",iconcolor="primary" if motor2 else "text2")
    # odômetros
    o=card(b,f,"cartao-odometro",16,648+d,328,72)
    for j,(ic,lab,val) in enumerate([("route","Odômetro atual","0,00 km"),("speed","Odômetro total","1.248 km")]):
        ox=14+j*160
        t=b.frame("ic-tile",ox,16,40,40,parent=o,fill="track",radius=12); b.icon(t,ic,8,8,24,"primary")
        b.text(o,lab,ox+50,14,size=11,color="text2",w=100)
        b.text(o,val,ox+50,32,size=17,weight="Bold",w=100)
    b.frame("div",164,14,1,44,parent=o,fill="stroke")
    # resumo da viagem atual (calculado pelo app a partir da telemetria; altimetria pelo GPS do celular)
    s=card(b,f,"cartao-viagem",16,732+d,328,184 if fixed else 148)
    b.text(s,"Viagem atual",14,10,size=13,weight="SemiBold",w=200)
    vals=[("speed","Velocidade média","16 km/h" if has else "0 km/h"),("trend","Velocidade máxima","27 km/h" if has else "0 km/h")]
    for k,(ic,lab,val) in enumerate(vals):
        cx0=14+k*160; cy0=40
        t_=b.frame("ic-tile",cx0,cy0,36,36,parent=s,fill="track",radius=10); b.icon(t_,ic,6,6,24,"primary")
        b.text(s,lab,cx0+44,cy0,size=11,color="text2",w=108)
        b.text(s,val,cx0+44,cy0+16,size=16,weight="Bold",w=108)
    if fixed:
        b.text(s,"Altimetria",14,92,size=12,color="text2",w=200)
        b.text(s,"+18 m" if has else "+0 m",14,110,size=28,weight="Bold",w=140,h=36)
        b.text(s,"ganho desde o início" if has else "sem ganho ainda",14,148,size=12,color="text2",w=150)
        alt=[2,2,3,4,6,7,7,9,10,12,12,11,13,15,16,16,17,18,18,18,18,18,18,18] if has else [0]*24
        for i,v in enumerate(alt):
            hh=max(3,v*2.2)
            b.frame("barra-altitude",168+i*6.4,152-hh,4,hh,parent=s,fill="primary" if has else "track",radius=2)
        b.text(s,"altitude (GPS)",168,156,size=10,color="text2",w=150)
    else:
        t_=b.frame("ic-tile",14,90,36,36,parent=s,fill="track",radius=10); b.icon(t_,"mountain",6,6,24,"primary")
        b.text(s,"Altimetria",58,90,size=11,color="text2",w=250)
        b.text(s,"+18 m" if has else "+0 m",58,106,size=16,weight="Bold",w=250)

    if fixed:
        # header fixo + rodapé fixo (conteúdo rola por trás)
        b.frame("faixa-topo",0,0,W,66,parent=outer,fill="bg"); header(outer)
        n=6
        for k in range(n):
            fr=b.frame("degrade",0,640+k*8,W,8,parent=outer,fill="bg"); b.add("node.set",{"id":fr,"opacity":(k+1)/n*0.9})
        b.frame("fundo-rodape",0,688,W,H-688,parent=outer,fill="bg")
        pb=b.frame("barra-fixa",0,690,W,H-690,parent=outer)
        bar(pb,W,0,H-690)
        b.refs[name+":reset"]=pb
        if toast: toast_pill(b,outer,toast,fs)
        return outer
    if overlay:
        from history import menu_popup
        r=menu_popup(b,f,148,60,[("Ver histórico","historico")])
        for k,v in r.items(): b.refs[name+":"+k]=v
    return f
