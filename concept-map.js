/* Offline, deterministic layout and navigation. The graph's identities and relations never change. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.VoiceConceptMap = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";
  const palette = [
    ["#203f37", "#92d6b6"],
    ["#233b54", "#9ecbfa"],
    ["#40324f", "#cbb0ed"],
    ["#443b25", "#edcd86"],
    ["#452f3d", "#e9b1ca"],
    ["#244247", "#91d7db"],
  ];
  function wrap(text, width, measure) {
    measure =
      measure ||
      ((value) =>
        Array.from(value).reduce(
          (n, c) =>
            n +
            (/[il.,' :!]/.test(c)
              ? 4
              : /[MW@\p{Script=Han}]/u.test(c)
                ? 14
                : 8),
          0,
        ));
    const lines = [];
    let line = "";
    for (const word of String(text).trim().split(/\s+/)) {
      if (measure((line ? line + " " : "") + word) <= width) {
        line += (line ? " " : "") + word;
        continue;
      }
      if (line) {
        lines.push(line);
        line = "";
      }
      for (const char of Array.from(word)) {
        if (line && measure(line + char) > width) {
          lines.push(line);
          line = "";
        }
        line += char;
      }
    }
    if (line) lines.push(line);
    return lines.length ? lines : [""];
  }
  function layout(map, { mode = "branches", measure } = {}) {
    const nodes = map.nodes.map((n) => ({
      ...n,
      uncertain: !!n.uncertain || /\[Non assegnato:/i.test(n.label),
    }));
    const byId = new Map(nodes.map((n) => [n.id, n])),
      order = new Map(nodes.map((n, i) => [n.id, i]));
    const outgoing = new Map(nodes.map((n) => [n.id, []])),
      incoming = new Map(nodes.map((n) => [n.id, []]));
    map.links.forEach((link, index) => {
      outgoing.get(link.from).push({ ...link, index });
      incoming.get(link.to).push({ ...link, index });
    });
    nodes.forEach((n) => {
      n.displayLabel = n.label.replace(/\[Non assegnato:\s*([^\]]*)\]/gi, "$1");
      n.lines = wrap(n.displayLabel, 208, measure);
      n.w = 240;
      n.h = Math.max(
        82,
        n.lines.length * 19 + 30 + (n.kind || n.uncertain ? 18 : 0),
      );
      n.children = [];
    });
    const reach = (id) => {
      const seen = new Set(),
        queue = [id];
      while (queue.length) {
        const next = queue.shift();
        if (seen.has(next)) continue;
        seen.add(next);
        outgoing.get(next).forEach((e) => queue.push(e.to));
      }
      return seen.size;
    };
    const unvisited = new Set(nodes.map((n) => n.id)),
      roots = [],
      treeEdges = new Set();
    while (unvisited.size) {
      let candidates = [...unvisited].filter((id) => !incoming.get(id).length);
      if (!candidates.length) candidates = [...unvisited];
      candidates.sort(
        (a, b) => reach(b) - reach(a) || order.get(a) - order.get(b),
      );
      if (!roots.length && map.root && unvisited.has(map.root))
        candidates = [map.root];
      const node = byId.get(candidates[0]);
      roots.push(node);
      unvisited.delete(node.id);
      node.depth = 0;
      const queue = [node];
      while (queue.length) {
        const parent = queue.shift();
        // Layout follows adjacency in either direction; arrow direction stays unchanged.
        const neighbors = [...outgoing.get(parent.id).map(edge=>({edge,id:edge.to})),...incoming.get(parent.id).map(edge=>({edge,id:edge.from}))];
        for (const {edge,id} of neighbors)
          if (unvisited.has(id)) {
            const child = byId.get(id);
            unvisited.delete(child.id);
            parent.children.push(child);
            child.parent = parent.id;
            child.depth = parent.depth + 1;
            treeEdges.add(edge.index);
            queue.push(child);
          }
      }
    }
    const edgeMeasure = (text) =>
      measure ? measure(text) * 0.8 : Array.from(text).length * 6.5;
    const metrics = map.links.map((link) => {
      const lines = link.label ? wrap(link.label, 176, edgeMeasure) : [];
      return {
        lines,
        labelW: lines.length
          ? Math.max(60, ...lines.map((line) => edgeMeasure(line) + 18))
          : 0,
        labelH: lines.length ? lines.length * 17 + 10 : 0,
      };
    });
    const siblingGap = Math.max(
      38,
      Math.max(0, ...metrics.map((m) => m.labelH)) * 2 - 66,
    );
    const span = (node) => {
      node.span = Math.max(
        node.h,
        node.children.reduce((n, c) => n + span(c), 0) +
          Math.max(0, node.children.length - 1) * siblingGap,
      );
      return node.span;
    };
    roots.forEach(span);
    let branch = 0,
      forestY = 0;
    function place(node, x, top, side, color) {
      node.x = x;
      node.y = top + (node.span - node.h) / 2;
      node.side = side;
      node.branch = color;
      let childY =
        top +
        (node.span -
          (node.children.reduce((n, c) => n + c.span, 0) +
            Math.max(0, node.children.length - 1) * siblingGap)) /
          2;
      for (const child of node.children) {
        place(child, x + side * 460, childY, side, color);
        childY += child.span + siblingGap;
      }
    }
    for (const node of roots) {
      if (mode === "levels") {
        place(node, 0, forestY, 1, branch++);
      } else {
        const sides = [[], []],
          heights = [0, 0];
        for (const child of node.children) {
          const side = heights[0] <= heights[1] ? 0 : 1;
          sides[side].push(child);
          heights[side] += child.span + siblingGap;
        }
        const height = Math.max(
          node.h,
          ...heights.map((h) => Math.max(0, h - siblingGap)),
        );
        node.x = 0;
        node.y = forestY + (height - node.h) / 2;
        node.branch = -1;
        node.side = 0;
        sides.forEach((children, index) => {
          let y =
            forestY + (height - Math.max(0, heights[index] - siblingGap)) / 2;
          for (const child of children) {
            place(child, index ? 460 : -460, y, index ? 1 : -1, branch++);
            y += child.span + siblingGap;
          }
        });
      }
      const members = nodes.filter((n) => n === node || rootId(n) === node.id);
      forestY = Math.max(...members.map((n) => n.y + n.h)) + 120;
    }
    // Colors follow semantic branches, even when the user switches layout.
    let color = 0;
    const paint = (node, index) => {
      node.branch = index;
      node.children.forEach((child) => paint(child, index));
    };
    roots.forEach((node) => {
      node.branch = -1;
      node.children.forEach((child) => paint(child, color++));
    });
    function rootId(node) {
      while (node.parent) node = byId.get(node.parent);
      return node.id;
    }
    let laneY = 20;
    metrics.forEach((metric, i) => {
      if (!treeEdges.has(i)) {
        metric.laneY = laneY + metric.labelH / 2;
        laneY += Math.max(20, metric.labelH) + 24;
      }
    });
    const topMargin = 48 + laneY,
      minX = Math.min(...nodes.map((n) => n.x)),
      minY = Math.min(...nodes.map((n) => n.y));
    nodes.forEach((n) => {
      n.x += 52 - minX;
      n.y += topMargin - minY;
      n.color = palette[(n.branch < 0 ? 0 : n.branch) % palette.length];
    });
    let width = Math.max(360, ...nodes.map((n) => n.x + n.w + 72)),
      height = Math.max(220, ...nodes.map((n) => n.y + n.h + 48)),
      cross = 0;
    const edges = map.links.map((link, index) => {
      const a = byId.get(link.from),
        b = byId.get(link.to);
      let d, lx, ly;
      if (treeEdges.has(index)) {
        const side = b.x > a.x ? 1 : -1,
          sx = side > 0 ? a.x + a.w : a.x,
          tx = side > 0 ? b.x : b.x + b.w,
          sy = a.y + a.h / 2,
          ty = b.y + b.h / 2;
        d = `M${sx},${sy} C${sx + side * 110},${sy} ${tx - side * 110},${ty} ${tx},${ty}`;
        lx = (sx + tx) / 2;
        ly = (sy + ty) / 2;
      } else {
        const sx = a.x + a.w,
          tx = b.x + b.w,
          sy = a.y + a.h / 2,
          ty = b.y + b.h / 2,
          lane1 = sx + 24 + (cross % 3) * 12,
          lane2 = tx + 24 + (cross % 3) * 12,
          y = metrics[index].laneY;
        d = `M${sx},${sy} H${lane1} V${y} H${lane2} V${ty} H${tx}`;
        lx = (lane1 + lane2) / 2;
        ly = y;
        cross++;
        width = Math.max(width, lane1 + 32, lane2 + 32);
      }
      return {
        ...link,
        index,
        d,
        lx,
        ly,
        ...metrics[index],
        cross: !treeEdges.has(index),
        color: (a.depth>b.depth?a:b).color[1],
      };
    });
    // Keep every relation label inside the complete export canvas.
    const labelLeft = Math.min(
        0,
        ...edges.map((e) => e.lx - e.labelW / 2 - 12),
      ),
      labelRight = Math.max(
        width,
        ...edges.map((e) => e.lx + e.labelW / 2 + 12),
      );
    if (labelLeft < 0) {
      nodes.forEach((n) => (n.x -= labelLeft));
      return layoutShift(
        {
          nodes,
          edges,
          width: labelRight - labelLeft,
          height,
          roots: roots.map((n) => n.id),
          mode,
        },
        -labelLeft,
      );
    }
    return {
      nodes,
      edges,
      width: labelRight,
      height,
      roots: roots.map((n) => n.id),
      mode,
    };
  }
  function layoutShift(value, dx) {
    value.edges.forEach((e) => {
      e.transform = `translate(${dx} 0)`;
    });
    return value;
  }
  function create(
    svg,
    viewport,
    map,
    { mode = "branches", onSelect = () => {}, onZoom = () => {} } = {},
  ) {
    const doc = svg.ownerDocument || document,
      ns = "http://www.w3.org/2000/svg",
      canvas = doc.createElement("canvas"),
      ctx = canvas.getContext("2d");
    if (ctx) ctx.font = "500 15px sans-serif";
    const graph = layout(map, {
      mode,
      measure: ctx?.measureText
        ? (text) => ctx.measureText(text).width
        : undefined,
    });
    const nodeElements = new Map(),
      edgeElements = [],
      listeners = [],
      pointers = new Map();
    let selected = null,
      scale = 1,
      cx = graph.width / 2,
      cy = graph.height / 2,
      drag = 0,
      start = null,
      pinch = null,
      candidate = null,
      viewMode = "fit",
      wasHidden = viewport.getBoundingClientRect().width === 0;
    const el = (tag, attrs = {}, text) => {
      const item = doc.createElementNS(ns, tag);
      Object.entries(attrs).forEach(([key, value]) =>
        item.setAttribute(key, String(value)),
      );
      if (text !== undefined) item.textContent = text;
      return item;
    };
    const listen = (target, type, fn, options) => {
      target.addEventListener(type, fn, options);
      listeners.push(() => target.removeEventListener(type, fn, options));
    };
    svg.innerHTML = "";
    svg.setAttribute("width", "100%");
    svg.setAttribute("height", "100%");
    svg.setAttribute("role", "group");
    svg.setAttribute("aria-label", map.topic + " · mappa interattiva");
    svg.appendChild(el("title", {}, map.topic));
    svg.appendChild(
      el(
        "desc",
        {},
        "Seleziona i concetti per leggere le relazioni. Trascina e usa i comandi di zoom per esplorare.",
      ),
    );
    const defs = el("defs");
    palette.forEach((colors, index) => {
      const marker = el("marker", {
        id: "mapArrow" + index,
        viewBox: "0 0 10 10",
        refX: 9,
        refY: 5,
        markerWidth: 6,
        markerHeight: 6,
        orient: "auto",
      });
      marker.appendChild(
        el("path", { d: "M0 0 L10 5 L0 10 Z", fill: colors[1] }),
      );
      defs.appendChild(marker);
    });
    svg.appendChild(defs);
    svg.appendChild(
      el("rect", {
        x: 0,
        y: 0,
        width: graph.width,
        height: graph.height,
        fill: "#101b21",
        "data-map-background": "true",
      }),
    );
    graph.edges.forEach((edge) => {
      const group = el("g", {
          "data-edge": edge.index,
          transform: edge.transform || "",
          class: "map-edge",
        }),
        colorIndex = Math.max(
          0,
          palette.findIndex((colors) => colors[1] === edge.color),
        );
      group.appendChild(
        el("path", {
          d: edge.d,
          fill: "none",
          stroke: edge.color,
          "stroke-width": 2,
          "stroke-linecap": "round",
          "marker-end": `url(#mapArrow${colorIndex})`,
          ...(edge.cross ? { "stroke-dasharray": "5 4" } : {}),
        }),
      );
      group.appendChild(
        el("title", {}, edge.label || "Relazione senza etichetta"),
      );
      if (edge.lines.length) {
        group.appendChild(
          el("rect", {
            x: edge.lx - edge.labelW / 2,
            y: edge.ly - edge.labelH / 2,
            width: edge.labelW,
            height: edge.labelH,
            rx: 7,
            fill: "#14242e",
            stroke: edge.color,
            "stroke-opacity": 0.5,
          }),
        );
        edge.lines.forEach((line, i) =>
          group.appendChild(
            el(
              "text",
              {
                x: edge.lx,
                y: edge.ly - (edge.lines.length - 1) * 8.5 + i * 17 + 4,
                "text-anchor": "middle",
                fill: "#cfdee7",
                "font-family": "sans-serif",
                "font-size": 12,
                "font-weight": 500,
              },
              line,
            ),
          ),
        );
      }
      edgeElements.push({ edge, group });
      svg.appendChild(group);
    });
    graph.nodes
      .slice()
      .sort((a, b) => a.depth - b.depth)
      .forEach((node) => {
        const group = el("g", {
          "data-node": node.id,
          class: "map-node",
          role: "button",
          tabindex: 0,
          "aria-label":
            node.label + (node.uncertain ? " · voce da verificare" : ""),
        });
        const rect = el("rect", {
          x: node.x,
          y: node.y,
          width: node.w,
          height: node.h,
          rx: 16,
          fill: node.color[0],
          stroke: node.color[1],
          "stroke-width": graph.roots.includes(node.id) ? 2.8 : 1.5,
        });
        group.appendChild(rect);
        group.appendChild(
          el(
            "title",
            {},
            [node.label, node.kind, node.detail, node.evidence]
              .filter(Boolean)
              .join("\n"),
          ),
        );
        const hasBadge = node.kind || node.uncertain,
          firstY =
            node.y +
            (node.h - node.lines.length * 19 - (hasBadge ? 20 : 0)) / 2 +
            14;
        node.lines.forEach((line, i) =>
          group.appendChild(
            el(
              "text",
              {
                x: node.x + 16,
                y: firstY + i * 19,
                fill: "#e1eee5",
                "font-family": "sans-serif",
                "font-size": 15,
                "font-weight": 500,
              },
              line,
            ),
          ),
        );
        if (hasBadge)
          group.appendChild(
            el(
              "text",
              {
                x: node.x + 16,
                y: node.y + node.h - 13,
                fill: node.uncertain ? "#f2ce85" : node.color[1],
                "font-family": "sans-serif",
                "font-size": 11,
              },
              node.uncertain
                ? (node.kind
                    ? node.kind.toLocaleUpperCase("it-IT") + " · "
                    : "") + "VOCE DA VERIFICARE"
                : node.kind.toLocaleUpperCase("it-IT"),
            ),
          );
        nodeElements.set(node.id, { node, group, rect });
        svg.appendChild(group);
        listen(group, "click", () => {
          if (drag < 8) select(node.id);
        });
        listen(group, "keydown", (event) => {
          if (["Enter", " "].includes(event.key)) {
            event.preventDefault();
            select(node.id);
          }
        });
        listen(group, "focus", () => {
          if (!pointers.size && selected !== node.id) select(node.id);
        });
      });
    function size() {
      const box = viewport.getBoundingClientRect();
      return { w: Math.max(240, box.width), h: Math.max(240, box.height) };
    }
    function fitScale() {
      const { w, h } = size();
      return Math.min(1, (w / graph.width) * 0.94, (h / graph.height) * 0.94);
    }
    function update() {
      const { w, h } = size();
      svg.setAttribute(
        "viewBox",
        `${cx - w / scale / 2} ${cy - h / scale / 2} ${w / scale} ${h / scale}`,
      );
      onZoom(scale);
    }
    function zoom(value, point) {
      viewMode = "manual";
      const { w, h } = size(),
        box = viewport.getBoundingClientRect(),
        x = point ? point.x - box.left : w / 2,
        y = point ? point.y - box.top : h / 2,
        wx = cx + (x - w / 2) / scale,
        wy = cy + (y - h / 2) / scale;
      scale = Math.max(Math.min(0.15, fitScale()), Math.min(2.5, value));
      cx = wx - (x - w / 2) / scale;
      cy = wy - (y - h / 2) / scale;
      update();
    }
    function clear() {
      selected = null;
      nodeElements.forEach(({ group, rect, node }) => {
        group.setAttribute("opacity", 1);
        group.setAttribute("aria-pressed", "false");
        rect.setAttribute(
          "stroke-width",
          graph.roots.includes(node.id) ? 2.8 : 1.5,
        );
      });
      edgeElements.forEach(({ group }) => group.setAttribute("opacity", 1));
      onSelect(null);
    }
    function fit() {
      clear();
      viewMode = "fit";
      scale = fitScale();
      cx = graph.width / 2;
      cy = graph.height / 2;
      update();
    }
    function select(id) {
      const record = nodeElements.get(id);
      if (!record) return;
      selected = id;
      viewMode = "manual";
      const related = new Set([id]);
      graph.edges.forEach((e) => {
        if (e.from === id) related.add(e.to);
        if (e.to === id) related.add(e.from);
      });
      nodeElements.forEach(({ node, group, rect }) => {
        group.setAttribute("opacity", related.has(node.id) ? 1 : 0.32);
        group.setAttribute("aria-pressed", String(node.id === id));
        rect.setAttribute("stroke-width", node.id === id ? 3.5 : 1.5);
      });
      edgeElements.forEach(({ edge, group }) =>
        group.setAttribute(
          "opacity",
          edge.from === id || edge.to === id ? 1 : 0.12,
        ),
      );
      scale = Math.max(scale, 0.85);
      cx = record.node.x + record.node.w / 2;
      cy = record.node.y + record.node.h / 2;
      update();
      onSelect(
        record.node,
        map.links.filter((e) => e.to === id),
        map.links.filter((e) => e.from === id),
      );
    }
    listen(
      svg,
      "wheel",
      (event) => {
        if (!event.ctrlKey && !event.metaKey) return;
        event.preventDefault();
        zoom(scale * Math.exp(-event.deltaY * 0.003), {
          x: event.clientX,
          y: event.clientY,
        });
      },
      { passive: false },
    );
    listen(svg, "pointerdown", (event) => {
      if (event.button && event.button !== 0) return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      svg.setPointerCapture?.(event.pointerId);
      if (pointers.size === 1) {
        drag = 0;
        candidate = event.target
          .closest?.("[data-node]")
          ?.getAttribute("data-node");
        start = { x: event.clientX, y: event.clientY, cx, cy };
      } else if (pointers.size === 2) {
        candidate = null;
        const [a, b] = [...pointers.values()];
        pinch = { distance: Math.hypot(a.x - b.x, a.y - b.y), scale };
      }
    });
    listen(svg, "pointermove", (event) => {
      if (!pointers.has(event.pointerId)) return;
      pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
      if (pointers.size === 2 && pinch) {
        const [a, b] = [...pointers.values()];
        drag = 20;
        zoom(
          (pinch.scale * Math.hypot(a.x - b.x, a.y - b.y)) /
            Math.max(1, pinch.distance),
          { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
        );
      } else if (start) {
        drag = Math.max(
          drag,
          Math.hypot(event.clientX - start.x, event.clientY - start.y),
        );
        viewMode = "manual";
        cx = start.cx - (event.clientX - start.x) / scale;
        cy = start.cy - (event.clientY - start.y) / scale;
        update();
      }
    });
    const release = (event) => {
      pointers.delete(event.pointerId);
      const tapped = !pointers.size && event.type === "pointerup" && drag < 8 ? candidate : null;
      if (!pointers.size) candidate = null;
      if (svg.hasPointerCapture?.(event.pointerId))
        svg.releasePointerCapture(event.pointerId);
      pinch = null;
      if (pointers.size) {
        const a = [...pointers.values()][0];
        start = { ...a, cx, cy };
      } else {
        start = null;
        if (tapped) select(tapped);
      }
    };
    listen(svg, "pointerup", release);
    listen(svg, "pointercancel", release);
    listen(svg, "lostpointercapture", release);
    const resize = () => {
      const box = viewport.getBoundingClientRect();
      if (!box.width || !box.height) {
        wasHidden = true;
        return;
      }
      if (wasHidden) {
        wasHidden = false;
        const focus = selected;
        fit();
        if (focus) select(focus);
      } else if (viewMode === "fit") fit();
      else update();
    };
    if (typeof ResizeObserver === "function") {
      const observer = new ResizeObserver(resize);
      observer.observe(viewport);
      listeners.push(() => observer.disconnect());
    }
    fit();
    return {
      graph,
      fit,
      select,
      zoomBy: (factor) => zoom(scale * factor),
      readable: () => {
        const node = graph.nodes.find(
          (n) => n.id === (selected || graph.roots[0]),
        );
        cx = node.x + node.w / 2;
        cy = node.y + node.h / 2;
        zoom(1);
      },
      refresh: resize,
      get selected() {
        return selected;
      },
      get scale() {
        return scale;
      },
      destroy: () => listeners.forEach((remove) => remove()),
      exportSvg: () => {
        const copy = svg.cloneNode(true);
        copy.setAttribute("viewBox", `0 0 ${graph.width} ${graph.height}`);
        copy.setAttribute("width", graph.width);
        copy.setAttribute("height", graph.height);
        copy.setAttribute("role", "img");
        copy.querySelectorAll("[data-node],[data-edge]").forEach((item) => {
          item.setAttribute("opacity", "1");
          item.removeAttribute("tabindex");
          item.removeAttribute("role");
          item.removeAttribute("aria-pressed");
          if (item.hasAttribute("data-node"))
            item
              .querySelector("rect")
              ?.setAttribute(
                "stroke-width",
                graph.roots.includes(item.getAttribute("data-node"))
                  ? 2.8
                  : 1.5,
              );
        });
        return copy;
      },
    };
  }
  return { layout, wrap, create, palette };
});
