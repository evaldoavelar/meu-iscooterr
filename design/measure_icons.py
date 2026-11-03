"""Mede largura/altura/min do traçado de cada ícone (o FigCraft soma o min NÃO escalado a x/y). Gera icons_dim.json."""
import json,subprocess,tempfile,os,sys
os.environ["MEASURING"]="1"
import lib
ICONS=lib.ICONS
C=os.environ.get("FIGCRAFT_BIN") or sys.argv[1]
cmds=[{"command":"frame.create","params":{"x":0,"y":0,"width":100,"height":100,"select":False},"as":"f"}]
for k,p in ICONS.items():
    cmds.append({"command":"vector.create","params":{"path":p,"x":0,"y":0,"parent":{"$ref":"f"},"name":k,"select":False},"as":k})
    cmds.append({"command":"node.get","params":{"id":{"$ref":k},"fields":["x","y","width","height"]}})
d=tempfile.mkdtemp(); p=os.path.join(d,"m.json"); json.dump(cmds,open(p,"w"))
out=subprocess.run([C,"run",p,"--out",os.path.join(d,"m.figcraft")],capture_output=True,text=True).stdout
res=[json.loads(x) for x in out.splitlines() if x.startswith("{") and '"node.get"' in x]
dim={k:[r["result"]["width"],r["result"]["height"],r["result"]["x"],r["result"]["y"]] for k,r in zip(ICONS,res)}
json.dump(dim,open(os.path.join(os.path.dirname(os.path.abspath(__file__)),"icons_dim.json"),"w"),indent=0)
print(len(dim),"ícones medidos")
