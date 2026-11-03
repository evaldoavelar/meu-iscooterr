import subprocess,sys,os
from lib import *; from home import home
b=B(); variables(b)
states=[("Bloqueado",dict(lock="locked")),("Desbloqueado",dict(lock="unlocked")),("Enviando-Lock",dict(pending="lock")),("Confirmado",dict(confirmed=True)),
 ("Conectando",dict(conn="conectando")),("Desconectado",dict(conn="desconectado")),("Em-Movimento",dict(lock="unlocked",moving=True)),("Falha",dict(fail=True)),
 ("Enviando-Farol",dict(pending="farol")),("Enviando-Tracao",dict(pending="tracao")),("Enviando-Modo",dict(pending="modo")),("Enviando-Cruise",dict(pending="cruise")),("Enviando-Partida",dict(pending="partida")),("Enviando-Limite",dict(pending="limite"))]
for page,mode in (("Home Escuro","Escuro"),("Home Claro","Claro")):
    if page=="Home Escuro": b.add("page.rename",{"name":page})
    else: b.add("page.add",{"name":page},"pg"); b.add("page.select",{"index":1})
    for i,(n,kw) in enumerate(states):
        f=home(b,(i%7)*400,(i//7)*860,f"Home-{n}",**kw)
        b.add("variable.setMode",{"ids":[f],"collection":"Cores","mode":mode})
b.dump("scripts/home.json")
