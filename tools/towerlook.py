"""Réplica exacta en Python de Template.kt, para calibrar desde capturas de adb."""
import numpy as np
from PIL import Image


def load(path):
    return np.array(Image.open(path).convert('RGB')).astype(np.int64)


def cell_average(px, x0, y0, x1, y1):
    h, w = px.shape[:2]
    sx = max(1, (x1 - x0) // 4)
    sy = max(1, (y1 - y0) // 4)
    ys = [y for y in range(y0, y1, sy) if y < h]
    xs = [x for x in range(x0, x1, sx) if x < w]
    if not ys or not xs:
        return 0
    block = px[np.ix_(ys, xs)].reshape(-1, 3)
    n = len(block)
    r, g, b = (block.sum(0) // n).tolist()
    return (r << 16) | (g << 8) | b


def sample_grid(px, box, cols, rows):
    l, t, r, b = box
    w, h = max(1, r - l), max(1, b - t)
    out = []
    for ri in range(rows):
        y0 = t + ri * h // rows
        y1 = max(y0 + 1, t + (ri + 1) * h // rows)
        for ci in range(cols):
            x0 = l + ci * w // cols
            x1 = max(x0 + 1, l + (ci + 1) * w // cols)
            out.append(cell_average(px, x0, y0, x1, y1))
    return out


def grid_size(box, max_cells):
    l, t, r, b = box
    w, h = max(1, r - l), max(1, b - t)
    if w >= h:
        cols = min(max_cells, w)
        rows = max(1, min(h, round(cols * h / w)))
    else:
        rows = min(max_cells, h)
        cols = max(1, min(w, round(rows * w / h)))
    return cols, rows


def template(px, box, max_cells=24):
    cols, rows = grid_size(box, max_cells)
    return {'box': {'left': box[0], 'top': box[1], 'right': box[2], 'bottom': box[3]},
            'cols': cols, 'rows': rows, 'rgb': sample_grid(px, box, cols, rows)}


def _rgb(vals):
    a = np.array(vals, dtype=np.int64)
    return np.stack([(a >> 16) & 255, (a >> 8) & 255, a & 255], 1)


def distance(t, px, box=None):
    b = box or tuple(t['box'][k] for k in ('left', 'top', 'right', 'bottom'))
    g = _rgb(sample_grid(px, b, t['cols'], t['rows']))
    return np.abs(_rgb(t['rgb']) - g).sum() / (len(g) * 3 * 255)


def similarity(t, px, box=None):
    b = box or tuple(t['box'][k] for k in ('left', 'top', 'right', 'bottom'))
    if b[1] < 0 or b[3] > px.shape[0]:
        return 0.0
    w = np.array([0.299, 0.587, 0.114])
    la = _rgb(t['rgb']) @ w
    lb = _rgb(sample_grid(px, b, t['cols'], t['rows'])) @ w
    da, db = ((la - la.mean()) ** 2).sum(), ((lb - lb.mean()) ** 2).sum()
    fa, fb = da / len(la) < 4, db / len(lb) < 4
    if fa or fb:
        return 1.0 if fa and fb and abs(la.mean() - lb.mean()) < 12 else 0.0
    return float(((la - la.mean()) * (lb - lb.mean())).sum() / np.sqrt(da * db))
