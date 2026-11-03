from lib import *
from home import home
from telemetry import telemetria
from history import historico, detalhe

states=[("Bloqueado",dict(lock="locked")),("Desbloqueado",dict(lock="unlocked")),("Enviando-Lock",dict(pending="lock")),("Confirmado",dict(confirmed=True)),
 ("Conectando",dict(conn="conectando")),("Desconectado",dict(conn="desconectado")),("Em-Movimento",dict(lock="unlocked",moving=True)),("Falha",dict(fail=True)),
 ("Enviando-Farol",dict(pending="farol")),("Enviando-Tracao",dict(pending="tracao")),("Enviando-Modo",dict(pending="modo")),("Enviando-Cruise",dict(pending="cruise")),("Enviando-Partida",dict(pending="partida")),("Enviando-Limite",dict(pending="limite"))]
tele=[("Telemetria-Parado",dict()),("Telemetria-Andando",dict(speed=18)),("Telemetria-1-Motor",dict(motor2=False)),
      ("Telemetria-Menu",dict(speed=18,overlay="menu")),("Telemetria-Zerar",dict(speed=18,overlay="dialogo"))]
b=B(); variables(b)

def nav(src,dst,**kw):
    b.add("proto.interaction.add",{"id":src,"dest":dst,"transition":{"type":"slideIn","direction":"right"},"durationMs":250,**kw})

for page,mode in (("Home Escuro","Escuro"),("Home Claro","Claro")):
    if page=="Home Escuro": b.add("page.rename",{"name":page})
    else: b.add("page.add",{"name":page},"pg"); b.add("page.select",{"index":1})
    def setm(f): b.add("variable.setMode",{"ids":[f],"collection":"Cores","mode":mode})
    homes={}; tels={}; hist={}
    for i,(n,kw) in enumerate(states):
        f=home(b,(i%7)*400,(i//7)*860,f"Home-{n}",**kw); homes[n]=f; setm(f)
    for k,(n,kw) in enumerate(tele):
        f=telemetria(b,k*400,1720,n,**kw); tels[n]=f; setm(f)
    hist["Historico"]=historico(b,0,2700,"Historico"); setm(hist["Historico"])
    hist["Historico-Vazio"]=historico(b,400,2700,"Historico-Vazio",vazio=True); setm(hist["Historico-Vazio"])
    for t in range(5):
        n=f"Viagem-{t+1}"; hist[n]=detalhe(b,(2+t)*400,2700,n,t); setm(hist[n])
    # interações (SIMULAÇÃO de navegação; dados de exemplo)
    for n in homes:
        for lab in ("Velocidade","Total"):
            r=b.refs.get(f"Home-{n}:{lab}")
            if r: nav(r,tels["Telemetria-Andando" if n=="Em-Movimento" else "Telemetria-Parado"])
    for n,f in tels.items(): b.add("proto.interaction.add",{"id":b.refs[n+":voltar"],"action":{"type":"back"}})
    for n in ("Telemetria-Parado","Telemetria-Andando","Telemetria-1-Motor"): nav(b.refs[n+":menu"],tels["Telemetria-Menu"])
    nav(b.refs["Telemetria-Menu:zerar"],tels["Telemetria-Zerar"]); nav(b.refs["Telemetria-Menu:historico"],hist["Historico"])
    b.add("proto.interaction.add",{"id":b.refs["Telemetria-Zerar:cancelar"],"dest":tels["Telemetria-Andando"]})
    b.add("proto.interaction.add",{"id":b.refs["Telemetria-Zerar:zerar"],"dest":tels["Telemetria-Parado"]})
    b.add("proto.interaction.add",{"id":b.refs["Historico:voltar"],"action":{"type":"back"}})
    for t in range(5):
        nav(b.refs[f"Historico:viagem-{t}"],hist[f"Viagem-{t+1}"])
        b.add("proto.interaction.add",{"id":b.refs[f"Viagem-{t+1}:voltar"],"action":{"type":"back"}})
    b.add("proto.flow.add",{"start":homes["Bloqueado"],"name":"Home → Telemetria → Histórico ("+mode+")"})
b.dump("scripts/home.json")
