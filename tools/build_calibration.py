"""Genera calibration.json para un Motorola Edge 50 Fusion (1080x2400) a partir de
capturas de adb de las tres listas de mejoras (tools/README en el repo)."""
import json
import sys
from towerlook import load, template, similarity

TOP, BOT = 1732, 2298          # parte visible de la lista
FIRST, PITCH, CARD = 1737, 209, 193
COLS = {'L': (40, 285, 292, 510), 'R': (557, 802, 808, 1026)}  # nombre x0,x1 / botón x0,x1

ROWS = {
    'ATTACK': [('DANO', 'VEL_ATAQUE'), ('PROB_CRITICO', 'FACTOR_CRITICO'), ('ALCANCE', 'DANO_METRO'),
               ('MULTI_PROB', 'MULTI_OBJ'), ('FUEGO_RAPIDO_PROB', 'FUEGO_RAPIDO_DUR'),
               ('REBOTE_PROB', 'REBOTE_OBJ'), ('REBOTE_ALCANCE', None)],
    'DEFENSE': [('SALUD', 'REGEN'), ('DEF_PCT', 'DEF_ABS'), ('ESPINAS', 'ROBO_VIDA'),
                ('KNOCKBACK_PROB', 'KNOCKBACK_FUERZA'), ('ESFERA_VEL', 'ESFERAS'),
                ('ONDA_TAMANO', 'ONDA_FREC')],
    'UTILITY': [('BONUS_DINERO', 'DINERO_OLEADA'), ('MONEDAS_MUERTE', 'MONEDAS_OLEADA'),
                ('ATAQUE_GRATIS', 'DEFENSA_GRATIS'), ('UTILIDAD_GRATIS', 'INTERES_OLEADA')],
}
PREFIX = {'ATTACK': 'atk', 'DEFENSE': 'def', 'UTILITY': 'uti'}
TAB_POINT = {'ATTACK': (135, 2347), 'DEFENSE': (403, 2347), 'UTILITY': (672, 2347)}


def card_tops(px):
    col = px[TOP:BOT, 40].sum(1) > 600
    starts = [TOP + i for i, v in enumerate(col) if v and (i == 0 or not col[i - 1])]
    # Un borde superior va 20 px después de un borde inferior, o 189 px antes de uno.
    tops = [s for s in starts if any(abs(s - 20 - o) <= 3 for o in starts) or any(abs(s + 189 - o) <= 3 for o in starts)]
    return [t for t in tops if not any(abs(t - 189 - o) <= 3 for o in starts) or any(abs(t - 20 - o) <= 3 for o in starts)]


def offsets(frames):
    """Cuánto se ha desplazado la lista en cada captura respecto a la primera (arriba del todo)."""
    out = [0]
    phase = lambda t: (t - FIRST) % PITCH
    prev = phase(card_tops(frames[0])[0])
    for px in frames[1:]:
        cur = phase(card_tops(px)[0])
        out.append(out[-1] + (prev - cur) % PITCH)
        prev = cur
    return out


def screen(sid, name, role, anchors, tap=None, wave=None, coins=None, tier=None, home=None, prev=None, nxt=None):
    box = lambda b: {'left': b[0], 'top': b[1], 'right': b[2], 'bottom': b[3]}
    d = {'id': sid, 'name': name, 'role': role, 'anchors': [template(load(p), b) for p, b in anchors]}
    if tap:
        d['tap'] = {'x': tap[0], 'y': tap[1]}
    if wave:
        d['waveBox'] = box(wave)
    if coins:
        d['coinsBox'] = box(coins)
    if tier:
        d['tierBox'] = box(tier)
    for key, p in (('homeTap', home), ('tierPrev', prev), ('tierNext', nxt)):
        if p:
            d[key] = {'x': p[0], 'y': p[1]}
    return d


def screens(shots):
    """shots: rutas de capturas de inicio, partida, fin de partida y el diálogo de salir."""
    return [
        screen('home', 'Inicio', 'HOME',
               [(shots['home'], (336, 324, 744, 396)), (shots['home'], (300, 1890, 780, 2020))], tap=(540, 1956),
               tier=(420, 1335, 660, 1405), prev=(390, 1370), nxt=(690, 1370)),
        # Iconos de $/monedas/gemas y la pestaña verde, que el bot nunca toca.
        screen('run', 'En partida', 'IN_RUN',
               [(shots['run'], (22, 130, 85, 335)), (shots['run'], (830, 2310, 1065, 2390))],
               wave=(565, 1512, 830, 1562), tier=(565, 1470, 760, 1516), coins=(88, 205, 330, 265)),
        screen('over', 'Fin de partida', 'GAME_OVER',
               [(shots['over'], (215, 660, 865, 735)), (shots['over'], (85, 1600, 515, 1720))],
               tap=(300, 1661), wave=(380, 760, 700, 835), coins=(100, 1455, 295, 1520),
               tier=(380, 850, 700, 925), home=(782, 1660)),
        screen('exit', 'Diálogo «Salir de la batalla»', 'POPUP',
               [(shots['exit'], (360, 960, 720, 1080))], tap=(905, 962)),
    ]


def main(seq_dir, out_path, shots=None):
    cal = {'gamePackage': 'com.TechTreeGames.TheTower', 'screenWidth': 1080, 'screenHeight': 2400,
           'screens': [], 'tabs': {}, 'slots': [], 'tabHeaders': {},
           'upgradeList': {'left': 0, 'top': TOP, 'right': 1080, 'bottom': BOT}}
    frames = {}
    for tab, rows in ROWS.items():
        fs = [load(f'{seq_dir}/{PREFIX[tab]}_{i}.png') for i in range(9)]
        frames[tab] = fs
        offs = offsets(fs)
        print(tab, 'desplazamientos', offs, file=sys.stderr)
        cal['tabs'][tab] = {'x': TAB_POINT[tab][0], 'y': TAB_POINT[tab][1]}
        cal['tabHeaders'][tab] = template(fs[0], (10, 1636, 700, 1724))
        for k, pair in enumerate(rows):
            for side, up in zip('LR', pair):
                if up is None:
                    continue
                # Una captura donde la tarjeta se vea entera.
                f = next(i for i, o in enumerate(offs)
                         if FIRST + PITCH * k - o >= TOP and FIRST + PITCH * k - o + CARD <= BOT)
                top = FIRST + PITCH * k - offs[f]
                nx0, nx1, bx0, bx1 = COLS[side]
                cal['slots'].append({
                    'upgrade': up, 'tab': tab, 'pos': 'TOP',
                    'box': {'left': nx0, 'top': top + 10, 'right': nx1, 'bottom': top + 183},
                    'look': template(fs[f], (nx0, top + 10, nx1, top + 183), 32),
                    'button': {'left': bx0, 'top': top + 23, 'right': bx1, 'bottom': top + 168},
                })
    if shots:
        cal['screens'] = screens(shots)
    json.dump(cal, open(out_path, 'w', encoding='utf-8'))
    return cal, frames


if __name__ == '__main__':
    # build_calibration.py <carpeta seq> <salida.json> <inicio.png> <partida.png> <fin.png> <salir.png>
    names = ['home', 'run', 'over', 'exit']
    main(sys.argv[1], sys.argv[2], dict(zip(names, sys.argv[3:7])) if len(sys.argv) >= 7 else None)
