"""Gerador FigCraft: helpers. Coordenadas absolutas dentro de frames 360x780."""
import json
APP_NAME = "Meu iScooter"
W, H = 360, 780
# Tokens: (Claro, Escuro)
COLORS = {
 "bg": ("#F4F7FB", "#0B1220"), "card": ("#FFFFFF", "#131C2B"), "stroke": ("#D3DCE8", "#22304A"),
 "track": ("#E3EAF3", "#1E2A3D"), "primary": ("#00A9C0", "#00E5FF"), "on-primary": ("#00262B", "#00262B"),
 "text": ("#0E1726", "#F2F6FA"), "text2": ("#52627A", "#9AA8BC"), "success": ("#0E9F63", "#19D68A"),
 "pending": ("#E67E00", "#FF9800"), "error": ("#D32F2F", "#FF5252"), "on-color": ("#101820", "#101820"),
 "disabled": ("#C9D3E0", "#2A3547"), "on-disabled": ("#6B7B92", "#7C8BA3"),
}
ICONS = {
 "gear": "M12 8.5a3.5 3.5 0 1 0 .001 0z M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2 M12 5.5a6.5 6.5 0 1 0 .001 0",
 "bluetooth": "M7 7l10 10-5 5V2l5 5L7 17",
 "battery": "M9 3h6v2h2v16H7V5h2z M10 12h4 M10 16h4",
 "speed": "M4 18a9 9 0 1 1 16 0 M12 15l4-6",
 "pin": "M12 21s6-5.5 6-11a6 6 0 1 0-12 0c0 5.5 6 11 6 11z M12 8a2 2 0 1 0 .001 0",
 "light": "M10 6h3a6 6 0 0 1 0 12h-3z M3 8h4 M3 12h4 M3 16h4",
 "motor": "M6 7h12v10H6z M3 10v4 M21 10v4 M9 4h6 M10 12h4",
 "cruise": "M12 3a9 9 0 1 0 .001 0z M12 12l4-4 M12 6v1.5",
 "rocket": "M12 3c3 2 5 5 5 9l-2 3H9l-2-3c0-4 2-7 5-9z M9 15l-3 3 M15 15l3 3 M12 9a1 1 0 1 0 .001 0",
 "lock": "M6 11h12v9H6z M8 11V8a4 4 0 0 1 8 0v3",
 "unlock": "M6 11h12v9H6z M8 11V8a4 4 0 0 1 7.6-1.8",
 "chev": "M9 6l6 6-6 6",
 "check": "M5 12l5 5 9-10",
 "alert": "M12 4l9 16H3z M12 10v4 M12 17v.1",
 "back": "M15 6l-6 6 6 6",
 "scooter": "M3 17a2 2 0 1 0 4 0 2 2 0 1 0-4 0 M17 17a2 2 0 1 0 4 0 2 2 0 1 0-4 0 M7 17h6l4-10 M15 5h4",
 "thermo": "M10 14V5a2 2 0 0 1 4 0v9a4 4 0 1 1-4 0z M12 9v8",
 "bolt": "M13 3L6 13h5l-1 8 7-10h-5z",
 "pulse": "M3 12h4l2-6 4 12 2-6h6",
 "route": "M5 19a2 2 0 1 0 .001 0 M19 5a2 2 0 1 0 .001 0 M7 19h6a3 3 0 0 0 0-6h-2a3 3 0 0 1 0-6h4",
 "spin": "M12 3a9 9 0 1 1-9 9",
}
import os
_f=os.path.join(os.path.dirname(os.path.abspath(__file__)),"icons_dim.json")
DIM={k:tuple(v) for k,v in json.load(open(_f)).items()} if os.path.exists(_f) else {}  # (w,h,minx,miny) medidos por measure_icons.py
class B:
    def __init__(s): s.cmds=[]; s.n=0; s.refs={}
    def add(s, cmd, params, as_=None):
        c={"command":cmd,"params":params}
        if as_: c["as"]=as_
        s.cmds.append(c); return {"$ref":as_} if as_ else None
    def uid(s,p="n"): s.n+=1; return f"{p}{s.n}"
    def frame(s,name,x,y,w,h,parent=None,fill=None,stroke=None,radius=0,sw=1,clip=False,effects=None):
        a=s.uid("f"); p={"name":name,"x":x,"y":y,"width":w,"height":h,"select":False}
        if parent: p["parent"]=parent
        s.add("frame.create",p,a); r={"$ref":a}
        q={"id":r,"fill":{"$var":fill} if fill else "none","cornerRadius":radius,"clip":clip}
        if stroke: q.update({"stroke":{"$var":stroke},"strokeWeight":sw,"strokeAlign":"inside"})
        else: q["stroke"]="none"
        if effects: q["effects"]=effects
        s.add("node.set",q); return r
    def text(s,parent,t,x,y,size=14,color="text",w=None,weight="Regular",align="left",name=None,h=None):
        a=s.uid("t"); p={"text":t,"x":x,"y":y,"parent":parent,"name":name or t[:24],"select":False,"props":{"fontSize":size,"fontStyle":weight,"textAlign":align}}
        if w: p["width"]=w
        s.add("text.create",p,a); r={"$ref":a}
        q={"id":r,"fill":{"$var":color}}
        if h: q.update({"textAutoResize":"none","textAlignVertical":"middle","height":h})
        s.add("node.set",q); return r
    def icon(s,parent,key,x,y,size=24,color="primary",sw=2):
        a=s.uid("i"); k=size/24; w,h,mx,my=DIM[key]
        s.add("vector.create",{"path":ICONS[key],"x":x+(size-w*k)/2-mx,"y":y+(size-h*k)/2-my,"width":w*k,"height":h*k,"parent":parent,"name":"ic-"+key,"select":False},a)
        r={"$ref":a}; s.add("node.set",{"id":r,"fill":"none","stroke":{"$var":color},"strokeWeight":sw,"strokeCap":"round","strokeJoin":"round"}); return r
    def dump(s,path): json.dump(s.cmds,open(path,"w"),ensure_ascii=False)

def variables(b):
    b.add("variable.createCollection",{"name":"Cores","modes":["Claro","Escuro"]})
    for k,(c,e) in COLORS.items():
        b.add("variable.create",{"collection":"Cores","name":k,"type":"color","values":{"Claro":c,"Escuro":e}})
