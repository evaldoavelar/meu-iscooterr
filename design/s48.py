"""S48: propostas isoladas para zoom e altimetria no detalhe da viagem.

Somente mockups, sem dados reais, rede ou implementação Android. Base: history.py
(detalhe/mapa/route), seletor e escala tipográfica existentes de s43.py.
Gestos propostos no mapa: pinça amplia/reduz; arrastar desloca; duplo toque amplia.
Qualquer mudança de câmera habilita centralizar no trajeto (ícone gps existente),
que restaura o enquadramento inicial. Nada disso é simulado por comandos reais.
"""
from lib import *
from home import card
from history import mapa, route, TRIPS
from s43 import S, T, th, hline, path, seletor

FR = []
Y0 = 46000  # abaixo dos quadros existentes; nenhuma função antiga é alterada

# Nomes dos frames = nomes dos PNGs antes do sufixo de tema, aplicado por run.sh.
CASES = (
    ("mapa-zoom-v1-ajustado", 1, "ajustado", None),
    ("mapa-zoom-v1-aproximado", 1, "aproximado", None),
    ("mapa-zoom-v1-recentrar", 1, "recentrar", None),
    ("mapa-zoom-v1-aproximado-fonte2", 2, "aproximado", None),
    ("altimetria-v1-preenchida", 1, None, True),
    ("altimetria-v1-sem-leitura", 1, None, False),
    ("altimetria-v1-preenchida-fonte2", 2, None, True),
)

# Fixture ilustrativa: distância em km, altitude GPS em m. Não é uma viagem real.
# Ganho 18 m, perda 12 m, mín. 742 m, máx. 754 m; início 742 m, fim 748 m.
# Os valores do card são explícitos, para conferência independente do desenho.
ELEVATION = ((0, 742), (.6, 746), (1.2, 751), (1.8, 747),
             (2.4, 754), (3.0, 750), (3.6, 747), (4.2, 749), (4.8, 748))


def map_chrome(b, m, w, h, fs, zoom=False, changed=False):
    """Escala e atribuição ficam no mapa, fora das áreas de toque dos controles."""
    scale = "50 m" if changed else "100 m"
    sh = th(scale, 10, 94, fs) + 14
    sc = b.frame("escala", 8, h-sh-8, 94, sh, parent=m,
                 fill="card", stroke="stroke", radius=6)
    b.text(sc, scale, 6, 2, size=S(fs, 10), w=82, color="text2")
    # mesma distância na tela = metade da distância no solo com zoom 2x
    hline(b, sc, 8, 86, sh-7, "text2", 1.5, name="barra-escala")
    for xx in (8, 86):
        path(b, sc, [(xx, sh-11), (xx, sh-7)], "text2", 1.5)
    ah = th("© OpenStreetMap", 10, 188, fs) + 8
    at = b.frame("atribuicao-osm", w-204, h-ah-8, 196, ah,
                 parent=m, fill="card", radius=6)
    b.text(at, "© OpenStreetMap", 4, 4, size=S(fs, 10), color="text2",
           w=188, align="right")
    if not zoom:
        return
    for i, sign in enumerate(("+", "−")):
        c = b.frame("ampliar" if i == 0 else "reduzir", w-56, 8+i*48,
                    48, 48, parent=m, fill="card", stroke="stroke", radius=10)
        # Sinais tipográficos: nenhum ícone novo fora de lib.py.
        b.text(c, sign, 0, 0, size=S(fs, 20), weight="SemiBold", w=48,
               h=48, align="center")
    c = b.frame("centralizar-no-trajeto", w-56, 112, 48, 48, parent=m,
                fill="primary" if changed else "disabled", radius=10)
    b.icon(c, "gps", 12, 12, 24, "on-primary" if changed else "on-disabled")


def map_view(b, p, x, y, w, h, fs, state=None):
    """Mesma geometria em todos os estados; câmera 2x e depois deslocamento.

    Basemap esquemático, como history.mapa; nomes de ruas são fictícios.
    Textos do mapa mantêm o tamanho, enquanto ruas/rota usam a mesma câmera.
    """
    seed = TRIPS[0][7]
    if state in (None, "ajustado"):
        m = mapa(b, p, x, y, w, h, seed, label=False)
        world = m
        z = 1
    else:
        m = b.frame("mapa", x, y, w, h, parent=p, fill="map-bg",
                    radius=20, clip=True)
        z = 2
        pts = route(seed, w, h)
        cx = (min(px for px, _ in pts)+max(px for px, _ in pts))/2
        cy = (min(py for _, py in pts)+max(py for _, py in pts))/2
        tx, ty = w/2-z*cx, h/2-z*cy
        if state == "recentrar":
            tx -= 72
            ty += 36
        world = mapa(b, m, tx, ty, w*z, h*z, seed, label=False, trajeto=False)
        path(b, world, [(px*z, py*z) for px, py in pts], "primary", 5,
             name="trajeto")
        for name, pt, col in (("inicio", pts[0], "success"),
                              ("fim", pts[-1], "primary")):
            b.frame(name, pt[0]*z-8, pt[1]*z-8, 16, 16, parent=world,
                    fill=col, stroke="card", radius=8, sw=2)
    # Âncoras geográficas iguais no enquadramento, zoom e arrasto.
    for label, rx, ry in (("Rua do Parque", .07, .22),
                          ("Rua das Flores", .23, .45),
                          ("Av. Central", .28, .70)):
        b.text(world, label, rx*w*z, ry*h*z-18, size=S(fs, 11),
               color="text2", w=200 if fs > 1 else 128)
    map_chrome(b, m, w, h, fs, zoom=state is not None,
               changed=state in ("aproximado", "recentrar"))
    return m


def statistics(b, f, y, fs, altitude=None):
    items = [("route", "Distância", TRIPS[0][1]),
             ("clock", "Duração", TRIPS[0][2]),
             ("speed", "Velocidade média", TRIPS[0][3]),
             ("trend", "Velocidade máxima", TRIPS[0][4]),
             ("mountain", "Altimetria", "+18 m" if altitude else "Sem leitura"),
             ("bolt", "Consumo médio", TRIPS[0][6])]
    # Mantém as seis posições originais, inclusive Consumo médio à direita.
    # O tile de altitude resume o ganho; o novo card abaixo detalha o perfil.
    # Mesmos rótulos 11 / valores 16 dp de history.detalhe e s45.detalhe.
    # Sem leitura tem 10 caracteres: largura útil preservada, sem fonte ampliada.
    if fs <= 1:
        height = 176
        c = card(b, f, "estatisticas", 16, y, 328, height)
        for k, (ic, label, value) in enumerate(items):
            xx, yy = 14+(k % 2)*160, 14+(k//2)*54
            tile = b.frame("ic-tile", xx, yy, 36, 36, parent=c,
                           fill="track", radius=10)
            b.icon(tile, ic, 6, 6, 24, "primary")
            b.text(c, label, xx+44, yy, size=11, color="text2", w=108)
            b.text(c, value, xx+44, yy+16, size=16, weight="Bold", w=108)
    else:
        height = 12+84*len(items)
        c = card(b, f, "estatisticas", 16, y, 328, height)
        for k, (ic, label, value) in enumerate(items):
            yy = 12+k*84
            tile = b.frame("ic-tile", 14, yy+14, 36, 36, parent=c,
                           fill="track", radius=10)
            b.icon(tile, ic, 6, 6, 24, "primary")
            b.text(c, label, 62, yy, size=S(fs, 11), color="text2", w=252)
            b.text(c, value, 62, yy+30, size=S(fs, 16), weight="Bold", w=252)
    return height


def elevation_card(b, f, y, fs, filled):
    c = card(b, f, "altimetria", 16, y, 328, 100)
    b.icon(c, "mountain", 14, 14, 24, "primary")
    cur = 14+T(b, c, "Altimetria", 48, 14, 13, fs, w=266,
               weight="SemiBold")+12
    if not filled:
        cur += T(b, c, "Sem leitura", 14, cur, 16, fs, w=300,
                 color="text2", weight="Bold")+14
        b.add("node.set", {"id": c, "height": cur})
        return cur
    # Mesma tipografia do detalhe; fonte 2,0 empilha sem reduzir os rótulos.
    metrics = (("Ganho", "+18 m"), ("Perda", "−12 m"),
               ("Altitude mín.", "742 m"), ("Altitude máx.", "754 m"))
    if fs <= 1:
        for k, (label, value) in enumerate(metrics):
            xx, yy = 14+(k % 2)*160, cur+(k//2)*46
            b.text(c, label, xx, yy, size=11, color="text2", w=140)
            b.text(c, value, xx, yy+16, size=16, weight="Bold", w=140)
        cur += 92
    else:
        for label, value in metrics:
            cur += T(b, c, label, 14, cur, 11, fs, w=300, color="text2")+2
            cur += T(b, c, value, 14, cur, 16, fs, w=300, weight="Bold")+10
    # Perfil por distância, com eixo vertical em metros e horizontal em km.
    label_w = 48 if fs <= 1 else 82
    gx, gw = 14+label_w, 300-label_w
    gh = 64 if fs <= 1 else 100
    top, base = cur+8, cur+8+gh
    for value, yy in ((754, top), (742, base)):
        b.text(c, f"{value} m", 14, yy-S(fs, 10)/2,
               size=S(fs, 10), color="text2", w=label_w-6)
        hline(b, c, gx, gx+gw, yy, "stroke", 1, name="grade-altitude")
    pts = [(gx+d/4.8*gw, base-(a-742)/12*gh) for d, a in ELEVATION]
    area = [(gx, base)]+pts+[(gx+gw, base)]
    a = b.uid("alt")
    b.add("vector.create", {"path": "M"+" L".join(f"{xx:.1f} {yy:.1f}" for xx, yy in area)+" Z",
                           "x": 0, "y": 0, "parent": c,
                           "name": "area-altitude", "select": False}, a)
    b.add("node.set", {"id": {"$ref": a}, "fill": {"$var": "primary"},
                       "stroke": "none", "opacity": .12})
    path(b, c, pts, "primary", 2, name="perfil-altitude")
    foot = base+12
    b.text(c, "0 km", gx, foot, size=S(fs, 10), color="text2", w=gw/2)
    b.text(c, "4,8 km", gx+gw/2, foot, size=S(fs, 10), color="text2",
           w=gw/2, align="right")
    cur = foot+th("4,8 km", 10, gw/2, fs)+14
    b.add("node.set", {"id": c, "height": cur})
    return cur


def detail(b, x, y, name, fs, map_state, altitude):
    f = b.frame(name, x, y, W, H, fill="bg", clip=True)
    FR.append(f)
    back = b.frame("voltar", 8, 16, 48, 48, parent=f)
    b.icon(back, "back", 12, 12, 24, "text")
    b.text(f, TRIPS[0][0], 56, 16, size=S(fs, 20), weight="Bold",
           w=W-112, align="center", h=48)
    # Mantém o cabeçalho do modelo history.py, sem criar ações extras.
    menu = b.frame("menu", W-56, 16, 48, 48, parent=f)
    for k in range(3):
        b.frame("ponto", 22, 15+k*7, 4, 4, parent=menu, fill="text2", radius=2)
    cur = 72
    hh = 48 if fs <= 1 else 66
    # Seleção Nenhum existente: sem acrescentar legenda/timeline de modos.
    seletor(b, f, 16, cur, 328, hh, 2, fs)
    cur += hh+10
    map_view(b, f, 16, cur, 328, 260, fs, state=map_state)
    cur += 260+14
    cur += statistics(b, f, cur, fs, altitude=altitude)+12
    if altitude is not None:
        cur += elevation_card(b, f, cur, fs, altitude)+12
    # Bateria e aviso de privacidade do detalhe original permanecem.
    bh = 56 if fs <= 1 else 112
    bt = card(b, f, "cartao-bateria", 16, cur, 328, bh)
    b.icon(bt, "battery", 14, 16, 24, "success")
    if fs <= 1:
        b.text(bt, "Bateria na viagem", 50, 0, size=13, color="text2", w=140, h=56)
        b.text(bt, "82% → 76%", 196, 0, size=16, weight="Bold", w=118,
               align="right", h=56)
    else:
        b.text(bt, "Bateria na viagem", 50, 12, size=S(fs, 13), color="text2", w=264)
        b.text(bt, "82% → 76%", 50, 62, size=S(fs, 16), weight="Bold", w=264)
    cur += bh+14
    cur += T(b, f, "Trajetos e viagens ficam salvos só no seu celular.",
             16, cur, 12, fs, w=328, color="text2", align="center")+20
    # Captura de conteúdo completo rolável; fonte 2,0 não é espremida em 780 dp.
    b.add("node.set", {"id": f, "height": max(H, cur)})
    return max(H, cur)


def s48_frames(b, setm):
    names = []
    for k, (name, fs, map_state, altitude) in enumerate(CASES):
        detail(b, k*400, Y0, name, fs, map_state, altitude)
        names.append(name)
    for f in FR:
        setm(f)
    FR.clear()
    return names
