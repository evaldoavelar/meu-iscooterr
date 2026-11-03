import subprocess,sys,os
from lib import *; from home import home
from telemetry import telemetria
b=B(); variables(b)
states=[("Bloqueado",dict(lock="locked")),("Desbloqueado",dict(lock="unlocked")),("Enviando-Lock",dict(pending="lock")),("Confirmado",dict(confirmed=True)),
 ("Conectando",dict(conn="conectando")),("Desconectado",dict(conn="desconectado")),("Em-Movimento",dict(lock="unlocked",moving=True)),("Falha",dict(fail=True)),
 ("Enviando-Farol",dict(pending="farol")),("Enviando-Tracao",dict(pending="tracao")),("Enviando-Modo",dict(pending="modo")),("Enviando-Cruise",dict(pending="cruise")),("Enviando-Partida",dict(pending="partida")),("Enviando-Limite",dict(pending="limite"))]
for page,mode in (("Home Escuro","Escuro"),("Home Claro","Claro")):
    if page=="Home Escuro": b.add("page.rename",{"name":page})
    else: b.add("page.add",{"name":page},"pg"); b.add("page.select",{"index":1})
    homes={}
    for i,(n,kw) in enumerate(states):
        f=home(b,(i%7)*400,(i//7)*860,f"Home-{n}",**kw); homes[n]=f
        b.add("variable.setMode",{"ids":[f],"collection":"Cores","mode":mode})
    tels={}
    for k,(n,kw) in enumerate([("Telemetria-Parado",dict()),("Telemetria-Andando",dict(speed=18)),("Telemetria-1-Motor",dict(motor2=False))]):
        f=telemetria(b,k*400,1720,n,**kw); tels[n]=f
        b.add("variable.setMode",{"ids":[f],"collection":"Cores","mode":mode})
    # interações: Velocidade/Total -> Telemetria (SIMULAÇÃO: telemetria de exemplo); voltar
    for n in homes:
        for lab in ("Velocidade","Total"):
            r=b.refs.get(f"Home-{n}:{lab}")
            if r: b.add("proto.interaction.add",{"id":r,"dest":tels["Telemetria-Andando" if n=="Em-Movimento" else "Telemetria-Parado"],"transition":{"type":"slideIn","direction":"right"},"durationMs":250})
    for n,f in tels.items():
        b.add("proto.interaction.add",{"id":b.refs[n+":voltar"],"action":{"type":"back"}})
    b.add("proto.flow.add",{"start":homes["Bloqueado"],"name":"Home → Telemetria ("+mode+")"})
b.dump("scripts/home.json")
