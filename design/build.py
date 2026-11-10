from lib import *
from home import home
from telemetry import telemetria
from history import historico, detalhe
from screens import *

states=[("Bloqueado",dict(lock="locked")),("Desbloqueado",dict(lock="unlocked")),("Enviando-Lock",dict(pending="lock")),("Confirmado",dict(confirmed=True)),("Confirmado-Desbloqueado",dict(confirmed=True,lock="unlocked")),
 ("Conectando",dict(conn="conectando")),("Desconectado",dict(conn="desconectado")),("Em-Movimento",dict(lock="unlocked",moving=True)),("Falha",dict(fail=True)),("Menu",dict(lock="unlocked",menu=True)),
 ("Enviando-Farol",dict(pending="farol")),("Enviando-Tracao",dict(pending="tracao")),("Enviando-Modo",dict(pending="modo")),("Enviando-Cruise",dict(pending="cruise")),("Enviando-Partida",dict(pending="partida")),("Enviando-Limite",dict(pending="limite"))]
tele=[("Telemetria-Parado",dict()),("Telemetria-Andando",dict(speed=18)),("Telemetria-Andando-GPS",dict(speed=17,src="gps")),("Telemetria-1-Motor",dict(motor2=False)),
      ("Telemetria-Pausada",dict(trip="pause")),("Telemetria-Barra-Fixa-Contando",dict(speed=18,fixed=True)),("Telemetria-Barra-Fixa-Pausada",dict(trip="pause",fixed=True)),("Telemetria-Menu",dict(speed=18,overlay="menu")),("Telemetria-Zerar",dict(speed=18,overlay="dialogo"))]
b=B(); variables(b)

def nav(src,dst,**kw):
    b.add("proto.interaction.add",{"id":src,"dest":dst,"transition":{"type":"slideIn","direction":"right"},"durationMs":250,**kw})

for page,mode in (("Home Escuro","Escuro"),("Home Claro","Claro")):
    if page=="Home Escuro": b.add("page.rename",{"name":page})
    else: b.add("page.add",{"name":page},"pg"); b.add("page.select",{"index":1})
    def setm(f): b.add("variable.setMode",{"ids":[f],"collection":"Cores","mode":mode})
    homes={}; tels={}; hist={}
    for i,(n,kw) in enumerate(states):
        f=home(b,(i%8)*400,(i//8)*860,f"Home-{n}",**kw); homes[n]=f; setm(f)
    for k,(n,kw) in enumerate(tele):
        f=telemetria(b,k*400,1720,n,**kw); tels[n]=f; setm(f)
    hist["Historico"]=historico(b,0,2700,"Historico"); setm(hist["Historico"])
    hist["Historico-Vazio"]=historico(b,400,2700,"Historico-Vazio",vazio=True); setm(hist["Historico-Vazio"])
    for t in range(5):
        n=f"Viagem-{t+1}"; hist[n]=detalhe(b,(2+t)*400,2700,n,t); setm(hist[n])
    ex={}
    ex["Configuracoes"]=configuracoes(b,0,4640,"Configuracoes")
    for k,e in enumerate((0,1,2)): ex[f"PrimeiroUso-{e+1}"]=primeiro_uso(b,(1+k)*400,4640,f"PrimeiroUso-{e+1}",e)
    ex["Recuperacao-Desligado"]=status(b,0,5600,"Recuperacao-Desligado","scooter","pending","Patinete não encontrado","Ele pode estar desligado ou longe. Ligue e chegue perto: o app reconecta sozinho.","Tentar agora")
    ex["Recuperacao-Bluetooth"]=status(b,400,5600,"Recuperacao-Bluetooth","bluetooth","pending","Bluetooth desligado","O app precisa do Bluetooth para falar com o patinete.","Ativar Bluetooth")
    ex["Recuperacao-Permissao"]=status(b,800,5600,"Recuperacao-Permissao","alert","error","Falta uma permissão","O Android pede acesso a “Dispositivos próximos” para achar o patinete. Usamos só para isso.","Permitir","primary","Abrir configurações do Android")
    ex["Recuperacao-Reconectando"]=status(b,1200,5600,"Recuperacao-Reconectando","spin","primary","Reconectando…","Tentativa 2 de 5. Mantenha o patinete ligado e por perto.","Cancelar","tonal")
    for f in ex.values(): setm(f)
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
    # navegação extra (protótipo; bloqueio simulado: toque -> enviando -> confirmado)
    H_=lambda n:homes[n]
    for n in ("Bloqueado","Desbloqueado","Falha"):
        nav(b.refs[f"Home-{n}:botao"],H_("Enviando-Lock"))
    b.add("proto.interaction.add",{"id":H_("Enviando-Lock"),"trigger":{"type":"afterDelay","ms":1200},"action":{"type":"navigate","dest":H_("Confirmado-Desbloqueado")}})
    b.add("proto.interaction.add",{"id":H_("Confirmado-Desbloqueado"),"trigger":{"type":"afterDelay","ms":2500},"action":{"type":"navigate","dest":H_("Desbloqueado")}})
    b.add("proto.interaction.add",{"id":H_("Confirmado"),"trigger":{"type":"afterDelay","ms":2500},"action":{"type":"navigate","dest":H_("Bloqueado")}})
    for n in homes:
        if n!="Menu": nav(b.refs[f"Home-{n}:menu"],homes["Menu"])
    b.add("proto.interaction.add",{"id":b.refs["Home-Menu:scrim"],"action":{"type":"back"}})
    nav(b.refs["Home-Menu:telemetria"],tels["Telemetria-Parado"]); nav(b.refs["Home-Menu:preferencias"],ex["Configuracoes"]); nav(b.refs["Home-Menu:historico"],hist["Historico"])
    b.add("proto.interaction.add",{"id":b.refs["Configuracoes:voltar"],"action":{"type":"back"}})
    nav(b.refs["PrimeiroUso-1:botao"],homes["Desconectado"])
    b.add("proto.interaction.add",{"id":ex["PrimeiroUso-1"],"trigger":{"type":"afterDelay","ms":2000},"action":{"type":"navigate","dest":ex["PrimeiroUso-2"]}})
    nav(b.refs["PrimeiroUso-2:botao"],ex["PrimeiroUso-3"]); nav(b.refs["PrimeiroUso-3:botao"],homes["Bloqueado"])
    nav(b.refs["Home-Desconectado:botao"],ex["Recuperacao-Reconectando"])
    b.add("proto.interaction.add",{"id":b.refs["Recuperacao-Reconectando:botao"],"dest":homes["Desconectado"]})
    for n in ("Desligado","Bluetooth","Permissao"): nav(b.refs[f"Recuperacao-{n}:botao"],ex["Recuperacao-Reconectando"])
    b.add("proto.flow.add",{"start":homes["Bloqueado"],"name":"Home → Telemetria → Histórico ("+mode+")"})
b.dump("scripts/home.json")
