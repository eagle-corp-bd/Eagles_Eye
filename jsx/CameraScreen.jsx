import { useState, useRef, useEffect, useCallback } from "react";
import { MoreHorizontal, Aperture, Video, Timer, Zap, RefreshCw, Image, SlidersHorizontal, Menu, Gauge, FastForward, PanelTop, Trash2, Share } from "lucide-react";

const GOLD = "#C9A96E";
const GOLD_HI = "#E8CD98";
const BG = "#030304";
const SURFACE = "#0A0A0C";
const BORDER = "#1C1C22";
const TEXT = "#F0EDE8";
const MUTED2 = "#3E3E48";
const RED = "#E23838";
const SPRING = "cubic-bezier(0.34,1.56,0.64,1)";
const FONT = "'SF Mono','Fira Code',monospace";
// Shared pill sizing tokens — every same-role pill (mode label, PRO tabs, AUTO
// toggle, dropdown options) uses these so heights line up across the whole app.
const PILL_PAD = "7px 13px";
const PILL_FONT = 8;
const PILL_RADIUS = 8;

const MODES = [
  { id: "pano", label: "PANO", icon: PanelTop },
  { id: "slomo", label: "SLO-MO", icon: Gauge },
  { id: "photo", label: "PHOTO", icon: Aperture },
  { id: "video", label: "VIDEO", icon: Video },
  { id: "hyperlapse", label: "HYPERLAPSE", icon: FastForward },
];

const Z_STOPS = [0.5, 1, 2, 3];
const Z_MIN = Z_STOPS[0];
const Z_MAX = 10;
const Z_MAJORS = [0.5, 1, 2, 3, 5, 7, 10];
const Z_COMPACT_W = 108, Z_COMPACT_H = 30, Z_EXPANDED_W = 260, Z_EXPANDED_H = 36;
const Z_PAD = 4, Z_THUMB = 24, Z_THUMB_EXP = 12, Z_DRAG_THRESHOLD = 7, Z_SNAP_EPS = 0.045, Z_FLICK_VEL = 3.5;
const Z_MM_W = 32, Z_BASE_MM = 18, Z_MINI = 36, Z_IDLE_MS = 8000;
const zClamp = (v, a, b) => Math.max(a, Math.min(b, v));
const zRound1 = (v) => Math.round(v * 10) / 10;
const zMm = (v) => Math.round(Z_BASE_MM * v);
function zBuzz(ms) { try { navigator.vibrate && navigator.vibrate(ms); } catch (e) {} }
function zIndexPos(v) {
  for (let i = 0; i < Z_STOPS.length - 1; i++) {
    if (v <= Z_STOPS[i + 1] || i === Z_STOPS.length - 2) {
      const t = (v - Z_STOPS[i]) / (Z_STOPS[i + 1] - Z_STOPS[i]);
      return i + zClamp(t, 0, 1);
    }
  }
  return 0;
}
function zFormat(v, expanded) {
  if (!expanded) {
    if (Math.abs(v - 0.5) < 0.02) return ".5×";
    if (Math.abs(v - Math.round(v)) < 0.02) return `${Math.round(v)}×`;
    return `${v.toFixed(1)}×`;
  }
  return `${v.toFixed(1)}×`;
}

const ASPECTS = [
  { value: "FULL", label: "FULL" }, { value: "16:9", label: "16:9" },
  { value: "3:2", label: "3:2" }, { value: "4:3", label: "4:3" },
  { value: "1:1", label: "1:1" }, { value: "9:16", label: "9:16" },
];
const TIMERS = [{ value: "off", label: "OFF" }, { value: "3s", label: "3S" }, { value: "10s", label: "10S" }];
const FLASHES = [{ value: "off", label: "OFF" }, { value: "auto", label: "AUTO" }, { value: "on", label: "ON" }];
const GRIDS = [
  { value: "off", label: "OFF" }, { value: "3x3", label: "3×3" }, { value: "4x4", label: "4×4" },
  { value: "golden", label: "GOLDEN" }, { value: "diagonal", label: "DIAG" },
];
const ASPECT_CSS = { "16:9": "9/16", "3:2": "2/3", "4:3": "3/4", "1:1": "1/1", "9:16": "16/9" };

function useLongPress(onLong, onTap, ms = 480) {
  const timer = useRef(null);
  const fired = useRef(false);
  const start = () => {
    fired.current = false;
    timer.current = setTimeout(() => { fired.current = true; onLong(); }, ms);
  };
  const clear = () => {
    clearTimeout(timer.current);
    if (!fired.current) onTap();
  };
  const cancel = () => clearTimeout(timer.current);
  return { onPointerDown: start, onPointerUp: clear, onPointerLeave: cancel };
}

// ── real glyph set from CameraTopBar: aspect/grid morph shapes + timer/flash/flip icons ──
function IconBox({ children }) {
  return <div style={{ width: 19, height: 19, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>{children}</div>;
}
function AspectGlyph({ ratio, c }) {
  const map = { FULL: [16, 16], "16:9": [17, 9.5], "3:2": [16, 10.7], "4:3": [15, 11.25], "1:1": [13, 13], "9:16": [9.5, 17] };
  const [w, h] = map[ratio] || [15, 11.25];
  return (
    <IconBox>
      <svg width="19" height="19" viewBox="0 0 19 19" style={{ overflow: "visible" }}>
        <rect x={(19 - w) / 2} y={(19 - h) / 2} width={w} height={h} rx="1.5" fill="none" stroke={c} strokeWidth="1.5" style={{ transition: `all 0.32s ${SPRING}` }} />
      </svg>
    </IconBox>
  );
}
function GridGlyph({ type, c }) {
  const lines = {
    off: { v: [], h: [] }, "3x3": { v: [6.33, 12.67], h: [6.33, 12.67] },
    "4x4": { v: [4.75, 9.5, 14.25], h: [4.75, 9.5, 14.25] }, golden: { v: [7.4, 11.6], h: [7.4, 11.6] },
    diagonal: { v: [], h: [] },
  };
  const { v, h } = lines[type] || lines.off;
  return (
    <IconBox>
      <svg width="19" height="19" viewBox="0 0 19 19">
        <rect x="2" y="2" width="15" height="15" rx="2" fill="none" stroke={c} strokeWidth="1.3" opacity={type === "off" ? 0.55 : 1} style={{ transition: "opacity 0.3s" }} />
        {type === "diagonal" && (<>
          <line x1="2" y1="2" x2="17" y2="17" stroke={c} strokeWidth="1" opacity="0.85" />
          <line x1="17" y1="2" x2="2" y2="17" stroke={c} strokeWidth="1" opacity="0.85" />
        </>)}
        {v.map((x, i) => <line key={"v" + i} x1={x} y1="2" x2={x} y2="17" stroke={c} strokeWidth="0.9" opacity="0.85" style={{ animation: `gridLineIn 0.3s ${SPRING} both`, animationDelay: `${i * 0.03}s` }} />)}
        {h.map((y, i) => <line key={"h" + i} x1="2" y1={y} x2="17" y2={y} stroke={c} strokeWidth="0.9" opacity="0.85" style={{ animation: `gridLineIn 0.3s ${SPRING} both`, animationDelay: `${(i + v.length) * 0.03}s` }} />)}
      </svg>
    </IconBox>
  );
}
const TopIcon = {
  timer: (c) => <IconBox><svg width="17" height="17" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="13" r="8" stroke={c} strokeWidth="1.6" /><path d="M12 9v4l3 2" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /><path d="M9.5 2h5" stroke={c} strokeWidth="1.6" strokeLinecap="round" /></svg></IconBox>,
  flash: (c, filled) => <IconBox><svg width="16" height="17" viewBox="0 0 24 24" fill={filled ? c : "none"}><path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" stroke={c} strokeWidth="1.5" strokeLinejoin="round" /></svg></IconBox>,
  flip: (c) => <IconBox><svg width="18" height="17" viewBox="0 0 24 24" fill="none"><path d="M4 9a8 8 0 0 1 13.5-4.5L20 7" stroke={c} strokeWidth="1.6" strokeLinecap="round" /><path d="M20 4v4h-4" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /><path d="M20 15a8 8 0 0 1-13.5 4.5L4 17" stroke={c} strokeWidth="1.6" strokeLinecap="round" /><path d="M4 20v-4h4" stroke={c} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg></IconBox>,
};
function BarControl({ icon, label, active, open, onToggle }) {
  const [pressed, setPressed] = useState(false);
  return (
    <button
      onClick={onToggle} onMouseDown={() => setPressed(true)} onMouseUp={() => setPressed(false)} onMouseLeave={() => setPressed(false)}
      onTouchStart={() => setPressed(true)} onTouchEnd={() => setPressed(false)}
      style={{
        position: "relative", display: "flex", flexDirection: "column", alignItems: "center", gap: 2,
        background: "transparent", border: "none", cursor: "pointer", padding: "8px 11px",
        transform: pressed ? "scale(0.86)" : open ? "scale(1.08)" : "scale(1)",
        transition: `transform 0.22s ${SPRING}`,
      }}
    >
      {icon}
      {label && <span style={{ fontFamily: FONT, fontSize: 7.5, letterSpacing: "0.08em", color: active ? GOLD : MUTED2, transition: "color 0.25s" }}>{label}</span>}
    </button>
  );
}
function OptionsPanel({ openId, options, value, onPick }) {
  const rowRef = useRef(null);
  const itemRefs = useRef({});
  const [pill, setPill] = useState({ left: 0, width: 0, ready: false });
  useEffect(() => {
    if (!openId) return;
    const el = itemRefs.current[value], row = rowRef.current;
    if (el && row) {
      const rb = row.getBoundingClientRect(), eb = el.getBoundingClientRect();
      setPill({ left: eb.left - rb.left, width: eb.width, ready: true });
    }
  }, [openId, value, options]);
  return (
    <div style={{
      position: "absolute", top: "calc(100% + 12px)", left: "50%",
      transform: `translateX(-50%) translateY(${openId ? "0" : "-8px"}) scale(${openId ? 1 : 0.9})`,
      opacity: openId ? 1 : 0, pointerEvents: openId ? "auto" : "none",
      transition: `transform 0.32s ${SPRING}, opacity 0.22s ease`, transformOrigin: "top center", zIndex: 50,
    }}>
      <div ref={rowRef} style={{ position: "relative", display: "flex", gap: 2, padding: 4, background: SURFACE, border: `1px solid ${BORDER}`, borderRadius: 999, boxShadow: "0 16px 44px #000000cc, inset 0 1px 0 #17171D", whiteSpace: "nowrap" }}>
        {pill.ready && <div style={{ position: "absolute", top: 4, bottom: 4, left: pill.left, width: pill.width, background: GOLD, borderRadius: 999, boxShadow: `0 2px 10px ${GOLD}66`, transition: `left 0.34s ${SPRING}, width 0.34s ${SPRING}` }} />}
        {options.map((opt, i) => {
          const isActive = opt.value === value;
          return (
            <button key={opt.value} ref={(el) => (itemRefs.current[opt.value] = el)} onClick={() => onPick(opt.value)}
              style={{
                position: "relative", zIndex: 1, fontFamily: FONT, fontSize: 9, fontWeight: isActive ? 700 : 400,
                letterSpacing: "0.03em", padding: PILL_PAD, borderRadius: 999, border: "none", cursor: "pointer",
                background: "transparent", color: isActive ? BG : `${TEXT}99`, transition: "color 0.22s",
                animation: openId ? `optPop 0.32s ${SPRING} both` : "none", animationDelay: `${i * 0.035}s`,
              }}
            >{opt.label}</button>
          );
        })}
      </div>
    </div>
  );
}

function Divider() {
  return <div style={{ width: 1, height: 16, background: `linear-gradient(to bottom, transparent, ${BORDER}, transparent)`, flexShrink: 0 }} />;
}

// ── top bar: real glyphs + shared options dropdown, collapsed behind a corner dot ──
function TopControls({ aspect, setAspect, grid, setGrid, timer, setTimer, flash, setFlash, flipped, setFlipped }) {
  const [open, setOpen] = useState(false);
  const [openId, setOpenId] = useState(null);
  const barRef = useRef(null);

  useEffect(() => {
    const onDoc = (e) => { if (barRef.current && !barRef.current.contains(e.target)) setOpenId(null); };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("touchstart", onDoc);
    return () => { document.removeEventListener("mousedown", onDoc); document.removeEventListener("touchstart", onDoc); };
  }, []);

  const toggleId = (id) => setOpenId(o => (o === id ? null : id));
  const panelFor = {
    aspect: { options: ASPECTS, value: aspect, onPick: (v) => { setAspect(v); setOpenId(null); } },
    grid: { options: GRIDS, value: grid, onPick: (v) => { setGrid(v); setOpenId(null); } },
    timer: { options: TIMERS, value: timer, onPick: (v) => { setTimer(v); setOpenId(null); } },
    flash: { options: FLASHES, value: flash, onPick: (v) => { setFlash(v); setOpenId(null); } },
  };
  const panel = panelFor[openId];
  const flashColor = flash === "on" ? GOLD_HI : flash === "auto" ? GOLD : `${TEXT}cc`;

  return (
    <div ref={barRef} style={{ position: "absolute", top: 20, right: 16, display: "flex", alignItems: "center", justifyContent: "flex-end", gap: 8, zIndex: 40 }}>
      <div style={{
        position: "relative", display: "flex", alignItems: "center", gap: 2,
        background: "rgba(10,10,12,0.72)", backdropFilter: "blur(18px)", WebkitBackdropFilter: "blur(18px)",
        border: `1px solid ${BORDER}`, borderRadius: 999,
        padding: open ? "2px 4px" : 0,
        maxWidth: open ? 460 : 0, opacity: open ? 1 : 0,
        overflow: open ? "visible" : "hidden", whiteSpace: "nowrap",
        transition: `max-width 0.42s ${SPRING}, opacity 0.28s ease, padding 0.3s ease`,
        boxShadow: open ? "0 16px 40px #000000aa, inset 0 1px 0 #ffffff08" : "none",
      }}>
        <BarControl icon={<AspectGlyph ratio={aspect} c={aspect !== "FULL" ? GOLD_HI : `${TEXT}cc`} />} label={aspect} active={aspect !== "FULL"} open={openId === "aspect"} onToggle={() => toggleId("aspect")} />
        <Divider />
        <BarControl icon={<GridGlyph type={grid} c={grid !== "off" ? GOLD_HI : `${TEXT}cc`} />} label={grid !== "off" ? GRIDS.find(g => g.value === grid)?.label : null} active={grid !== "off"} open={openId === "grid"} onToggle={() => toggleId("grid")} />
        <Divider />
        <BarControl icon={TopIcon.timer(timer !== "off" ? GOLD_HI : `${TEXT}cc`)} label={timer !== "off" ? timer.toUpperCase() : null} active={timer !== "off"} open={openId === "timer"} onToggle={() => toggleId("timer")} />
        <Divider />
        <BarControl icon={TopIcon.flash(flashColor, flash === "on")} label={flash !== "off" ? flash.toUpperCase() : null} active={flash !== "off"} open={openId === "flash"} onToggle={() => toggleId("flash")} />
        <Divider />
        <button onClick={() => setFlipped(f => !f)} style={{ background: "transparent", border: "none", cursor: "pointer", padding: "10px 12px", display: "flex" }}>
          <div style={{ transform: `rotateY(${flipped ? 180 : 0}deg) scale(${flipped ? 1.06 : 1})`, transition: `transform 0.5s ${SPRING}` }}>
            {TopIcon.flip(flipped ? GOLD_HI : `${TEXT}cc`)}
          </div>
        </button>

        {panel && <OptionsPanel openId={openId} options={panel.options} value={panel.value} onPick={panel.onPick} />}
      </div>

      <button
        onClick={() => { setOpen(o => !o); setOpenId(null); }}
        style={{
          width: 36, height: 36, borderRadius: "50%", flexShrink: 0,
          background: open ? GOLD : "rgba(10,10,12,0.72)",
          border: `1px solid ${open ? GOLD : BORDER}`,
          backdropFilter: "blur(18px)", WebkitBackdropFilter: "blur(18px)",
          display: "flex", alignItems: "center", justifyContent: "center", cursor: "pointer",
          transform: open ? "rotate(90deg) scale(1.05)" : "rotate(0deg) scale(1)",
          transition: `all 0.36s ${SPRING}`,
        }}
      >
        <MoreHorizontal size={18} color={open ? BG : `${TEXT}cc`} strokeWidth={2} />
      </button>
    </div>
  );
}

// ── mode pill: tap toggles photo/video, long-press reveals full mode carousel ──
// ── mini goo-blob switch — spring-settle glide between PHOTO/VIDEO, adapted from PhotoVideoSwitch ──
function MiniGooSwitch({ mode }) {
  const target = mode === "video" ? 1 : 0;
  const [pos, setPos] = useState(target);
  const [pulseTick, setPulseTick] = useState(0);
  const posRef = useRef(target), velRef = useRef(0), rafRef = useRef(null), mountedRef = useRef(false);

  useEffect(() => {
    let last = performance.now();
    const stiffness = 420, damping = 21;
    const step = (now) => {
      const dt = Math.min((now - last) / 1000, 0.032); last = now;
      const force = -stiffness * (posRef.current - target);
      const damp = -damping * velRef.current;
      velRef.current += (force + damp) * dt;
      posRef.current += velRef.current * dt;
      setPos(posRef.current);
      if (Math.abs(posRef.current - target) > 0.0015 || Math.abs(velRef.current) > 0.0015) {
        rafRef.current = requestAnimationFrame(step);
      } else {
        posRef.current = target; velRef.current = 0; setPos(target);
        if (mountedRef.current) setPulseTick(t => t + 1);
        mountedRef.current = true;
      }
    };
    rafRef.current = requestAnimationFrame(step);
    return () => cancelAnimationFrame(rafRef.current);
  }, [target]);

  const W = 88, H = 26, PAD = 3, HALF = (W - PAD * 2) / 2, BLOB_W = HALF - 3, BLOB_H = H - PAD * 2;
  const p = pos;

  return (
    <div style={{
      position: "relative", width: W, height: H, borderRadius: 999, overflow: "hidden",
      background: "linear-gradient(155deg, rgba(28,28,31,0.88), rgba(3,3,4,0.94))",
      border: `1px solid ${BORDER}`, boxShadow: "inset 0 1px 0 rgba(255,255,255,0.07), 0 4px 12px rgba(0,0,0,0.5)",
    }}>
      <div style={{ position: "absolute", inset: 0, filter: "url(#mgs-grain)", opacity: 0.4, mixBlendMode: "overlay", animation: "grainMove 0.4s steps(2) infinite" }} />
      <div key={pulseTick} className={mountedRef.current ? "mgs-pulse" : ""} style={{
        position: "absolute", top: PAD, left: PAD + 1, width: BLOB_W, height: BLOB_H, borderRadius: 999,
        background: `linear-gradient(160deg, ${GOLD_HI} 0%, ${GOLD} 55%, #9C7C4A 100%)`,
        boxShadow: "0 2px 8px rgba(201,169,110,0.4)",
        transform: `translateX(${p * HALF}px)`,
      }} />
      <div style={{ position: "relative", display: "grid", gridTemplateColumns: "1fr 1fr", width: "100%", height: "100%" }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 4, color: p < 0.5 ? BG : `${TEXT}99`, opacity: 0.5 + 0.5 * (1 - Math.min(1, Math.max(0, p))), fontSize: 8, fontWeight: 700, fontFamily: FONT, letterSpacing: "0.06em", transition: "color 0.15s" }}>
          <Aperture size={10} strokeWidth={2.2} />PHOTO
        </div>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 4, color: p > 0.5 ? BG : `${TEXT}99`, opacity: 0.5 + 0.5 * Math.min(1, Math.max(0, p)), fontSize: 8, fontWeight: 700, fontFamily: FONT, letterSpacing: "0.06em", transition: "color 0.15s" }}>
          <Video size={10} strokeWidth={2.2} />VIDEO
        </div>
      </div>
    </div>
  );
}

function ModePill({ mode, setMode, disabled }) {
  const [expanded, setExpanded] = useState(false);
  const collapseTimer = useRef(null);

  const openCarousel = () => { if (navigator.vibrate) navigator.vibrate(10); setExpanded(true); };
  const tapToggle = () => setMode(m => (m === "video" ? "photo" : "video"));
  const press = useLongPress(openCarousel, tapToggle);

  const pick = (id) => {
    setMode(id);
    if (navigator.vibrate) navigator.vibrate(8);
    clearTimeout(collapseTimer.current);
    collapseTimer.current = setTimeout(() => setExpanded(false), 260);
  };

  useEffect(() => () => clearTimeout(collapseTimer.current), []);

  const activeMeta = MODES.find(m => m.id === mode) || MODES[2];

  return (
    <div style={{
      position: "absolute", top: 20, left: 16,
      zIndex: 40, opacity: disabled ? 0 : 1, pointerEvents: disabled ? "none" : "auto",
      transition: "opacity 0.25s ease",
    }}>
      <div style={{
        position: "relative", display: "flex", alignItems: "center",
        background: "rgba(10,10,12,0.78)", backdropFilter: "blur(16px)", WebkitBackdropFilter: "blur(16px)",
        border: `1px solid ${BORDER}`, borderRadius: 999,
        padding: 4, gap: 2,
        boxShadow: "0 10px 30px #000000aa, inset 0 1px 0 #ffffff08",
        transition: `width 0.4s ${SPRING}`,
      }}>
        {!expanded ? (
          <div {...press} style={{ cursor: "pointer", display: "flex", alignItems: "center" }}>
            {(mode === "photo" || mode === "video") ? (
              <MiniGooSwitch mode={mode} />
            ) : (
              <div style={{ display: "flex", alignItems: "center", gap: 6, padding: PILL_PAD, fontFamily: FONT }}>
                <activeMeta.icon size={13} color={GOLD_HI} strokeWidth={2} />
                <span style={{ fontSize: 9, letterSpacing: "0.1em", color: GOLD_HI, fontWeight: 600 }}>{activeMeta.label}</span>
              </div>
            )}
          </div>
        ) : (
          MODES.map((m, i) => {
            const isActive = m.id === mode;
            return (
              <button
                key={m.id}
                onClick={() => pick(m.id)}
                style={{
                  display: "flex", flexDirection: "column", alignItems: "center", gap: 3,
                  background: isActive ? GOLD : "transparent", border: "none", cursor: "pointer",
                  padding: PILL_PAD, borderRadius: 999,
                  transition: `background 0.25s ease, transform 0.3s ${SPRING}`,
                  animation: `modePop 0.32s ${SPRING} both`, animationDelay: `${i * 0.03}s`,
                }}
              >
                <m.icon size={13} color={isActive ? BG : `${TEXT}aa`} strokeWidth={2} />
                <span style={{ fontFamily: FONT, fontSize: 6.5, letterSpacing: "0.05em", color: isActive ? BG : `${TEXT}77`, fontWeight: isActive ? 700 : 400 }}>
                  {m.label}
                </span>
              </button>
            );
          })
        )}
      </div>
    </div>
  );
}

// ── simplified shutter — tap capture, hold arms video, drag down bursts ────────
function Shutter({ mode, isRecording, onArmVideo, onStopVideo, onCapture }) {
  const [pressed, setPressed] = useState(false);
  const [holdProgress, setHoldProgress] = useState(0);
  const [ripple, setRipple] = useState(null);
  const holdStart = useRef(null);
  const raf = useRef(null);
  const fired = useRef(false);
  const active = useRef(false);

  const isVideoMode = mode === "video";

  const spawnRipple = () => { const id = Date.now(); setRipple(id); setTimeout(() => setRipple(r => (r === id ? null : r)), 600); };

  const down = () => {
    active.current = true; setPressed(true);
    if (isRecording) return;
    if (isVideoMode) { onArmVideo(); spawnRipple(); return; }
    holdStart.current = performance.now(); fired.current = false;
    const tick = () => {
      if (!active.current) return;
      const p = Math.min((performance.now() - holdStart.current) / 750, 1);
      setHoldProgress(p);
      if (p >= 1 && !fired.current) { fired.current = true; onArmVideo(); }
      if (p < 1) raf.current = requestAnimationFrame(tick);
    };
    raf.current = requestAnimationFrame(tick);
  };

  const up = () => {
    active.current = false; setPressed(false);
    cancelAnimationFrame(raf.current); setHoldProgress(0);
    if (isRecording) { onStopVideo(); spawnRipple(); return; }
    if (fired.current) { fired.current = false; return; }
    if (!isVideoMode) { onCapture(); spawnRipple(); }
  };

  const ringC = 2 * Math.PI * 34;

  return (
    <div style={{ position: "relative", width: 82, height: 82, display: "flex", alignItems: "center", justifyContent: "center" }}>
      {ripple && (
        <span style={{ position: "absolute", width: 70, height: 70, borderRadius: "50%", border: `1.5px solid ${isRecording || isVideoMode ? RED : GOLD_HI}`, animation: "shutterRipple 0.6s cubic-bezier(0.16,1,0.3,1) forwards" }} />
      )}
      <svg width="78" height="78" style={{ position: "absolute", transform: "rotate(-90deg)" }}>
        <circle cx="39" cy="39" r="34" fill="none" stroke="rgba(226,56,56,0.15)" strokeWidth="2" />
        <circle cx="39" cy="39" r="34" fill="none" stroke={RED} strokeWidth="2" strokeLinecap="round"
          strokeDasharray={ringC} strokeDashoffset={ringC * (1 - holdProgress)}
          style={{ opacity: pressed && !isVideoMode && !isRecording ? 1 : 0, transition: "stroke-dashoffset 30ms linear" }} />
      </svg>
      {isRecording && (
        <svg width="88" height="88" style={{ position: "absolute", animation: "spin 4s linear infinite" }}>
          <circle cx="44" cy="44" r="38" fill="none" stroke={RED} strokeWidth="1.6" strokeDasharray="5 7" opacity="0.7" />
        </svg>
      )}
      <div style={{
        width: 70, height: 70, borderRadius: "50%",
        background: "linear-gradient(145deg,#1c1c1c,#050505)",
        boxShadow: "0 0 0 1px rgba(201,169,110,0.3), 0 6px 18px rgba(0,0,0,0.6)",
        display: "flex", alignItems: "center", justifyContent: "center",
        transform: pressed ? "scale(0.93)" : "scale(1)", transition: `transform 0.18s ${SPRING}`,
      }}>
        <button
          onPointerDown={down} onPointerUp={up} onPointerLeave={() => active.current && up()} onPointerCancel={up}
          style={{
            width: 52, height: 52, border: "none", cursor: "pointer",
            borderRadius: isRecording ? 16 : "50%",
            background: isRecording ? "linear-gradient(145deg,#FF6B6B,#E23838)" : `linear-gradient(145deg,${GOLD_HI},${GOLD})`,
            boxShadow: pressed ? "inset 0 2px 6px rgba(0,0,0,0.4)" : `0 2px 10px ${isRecording ? "rgba(226,56,56,0.5)" : "rgba(201,169,110,0.5)"}`,
            transition: `border-radius 0.3s ${SPRING}, transform 0.15s ${SPRING}, background 0.25s ease`,
          }}
        />
      </div>
    </div>
  );
}

function ZoomPill({ disabled }) {
  const [value, setValue] = useState(1);
  const [springTarget, setSpringTarget] = useState(1);
  const [isExpanded, setIsExpanded] = useState(false);
  const [isMini, setIsMini] = useState(false);
  const [pressed, setPressed] = useState(false);
  const [pulseTick, setPulseTick] = useState(0);

  const valueRef = useRef(1), velRef = useRef(0), rafRef = useRef(null);
  const draggingRef = useRef(false), movedRef = useRef(false), isExpandedRef = useRef(false);
  const startXRef = useRef(0), lastXRef = useRef(0), startValueRef = useRef(1);
  const lastGridRef = useRef(10), lastSnapRef = useRef(null);
  const collapseTimerRef = useRef(null), miniTimerRef = useRef(null), mountedRef = useRef(false);
  const trackRef = useRef(null);

  const armMiniTimer = () => {
    clearTimeout(miniTimerRef.current);
    miniTimerRef.current = setTimeout(() => {
      if (!draggingRef.current && !isExpandedRef.current) setIsMini(true);
    }, Z_IDLE_MS);
  };

  useEffect(() => { armMiniTimer(); return () => { clearTimeout(collapseTimerRef.current); clearTimeout(miniTimerRef.current); }; }, []);

  const onMiniTap = () => { clearTimeout(miniTimerRef.current); setIsMini(false); zBuzz(4); armMiniTimer(); };

  useEffect(() => {
    if (!isExpanded) return;
    const handler = (e) => {
      if (trackRef.current && !trackRef.current.contains(e.target)) {
        clearTimeout(collapseTimerRef.current); isExpandedRef.current = false; setIsExpanded(false);
      }
    };
    document.addEventListener("pointerdown", handler);
    return () => document.removeEventListener("pointerdown", handler);
  }, [isExpanded]);

  useEffect(() => {
    if (draggingRef.current) return;
    let last = performance.now();
    const stiffness = 520, damping = 26;
    const step = (now) => {
      const dt = Math.min((now - last) / 1000, 0.032); last = now;
      const force = -stiffness * (valueRef.current - springTarget);
      const damp = -damping * velRef.current;
      velRef.current += (force + damp) * dt;
      valueRef.current += velRef.current * dt;
      setValue(valueRef.current);
      if (Math.abs(valueRef.current - springTarget) > 0.0015 || Math.abs(velRef.current) > 0.0015) {
        rafRef.current = requestAnimationFrame(step);
      } else {
        valueRef.current = springTarget; velRef.current = 0; setValue(springTarget);
        if (mountedRef.current) setPulseTick((t) => t + 1);
        mountedRef.current = true;
      }
    };
    rafRef.current = requestAnimationFrame(step);
    return () => cancelAnimationFrame(rafRef.current);
  }, [springTarget]);

  const commitValue = (target, changed) => {
    const clamped = zClamp(zRound1(target), Z_MIN, Z_MAX);
    setSpringTarget(clamped); zBuzz(changed ? 9 : 3);
  };

  const scheduleCollapse = () => {
    clearTimeout(collapseTimerRef.current);
    collapseTimerRef.current = setTimeout(() => { isExpandedRef.current = false; setIsExpanded(false); armMiniTimer(); }, 900);
  };

  const onPointerDown = (e) => {
    if (isMini) return;
    draggingRef.current = true; movedRef.current = false; setPressed(true);
    cancelAnimationFrame(rafRef.current); clearTimeout(collapseTimerRef.current); clearTimeout(miniTimerRef.current);
    startXRef.current = e.clientX; lastXRef.current = e.clientX; startValueRef.current = valueRef.current;
    lastGridRef.current = Math.round(valueRef.current * 10); lastSnapRef.current = null;
    trackRef.current.setPointerCapture(e.pointerId);
  };

  const onPointerMove = (e) => {
    if (!draggingRef.current || !trackRef.current) return;
    if (!isExpandedRef.current) {
      if (Math.abs(e.clientX - startXRef.current) > Z_DRAG_THRESHOLD) {
        isExpandedRef.current = true; setIsExpanded(true); lastXRef.current = e.clientX; zBuzz(5);
      }
      return;
    }
    movedRef.current = true;
    const width = trackRef.current.offsetWidth, usable = width - 2 * Z_PAD;
    const dxStep = e.clientX - lastXRef.current; lastXRef.current = e.clientX;
    const deltaValue = (dxStep / usable) * (Z_MAX - Z_MIN);
    let next = valueRef.current + deltaValue;
    if (next < Z_MIN) next = Z_MIN + (next - Z_MIN) * 0.3;
    if (next > Z_MAX) next = Z_MAX + (next - Z_MAX) * 0.3;
    velRef.current = deltaValue * 70; valueRef.current = next; setValue(next);
    const gridNow = Math.round(next * 10);
    if (gridNow !== lastGridRef.current) { zBuzz(2); lastGridRef.current = gridNow; }
    const nearStop = Z_STOPS.find((st) => Math.abs(next - st) < Z_SNAP_EPS);
    if (nearStop !== undefined && lastSnapRef.current !== nearStop) { zBuzz(6); lastSnapRef.current = nearStop; }
    else if (nearStop === undefined) lastSnapRef.current = null;
  };

  const onPointerUp = (e) => {
    if (!draggingRef.current) return;
    draggingRef.current = false; setPressed(false);
    if (!isExpandedRef.current) {
      const rect = trackRef.current.getBoundingClientRect();
      const frac = zClamp((e.clientX - rect.left) / rect.width, 0, 1);
      const idx = Math.round(frac * (Z_STOPS.length - 1));
      commitValue(Z_STOPS[idx], Z_STOPS[idx] !== startValueRef.current);
      armMiniTimer();
    } else if (!movedRef.current) {
      const rect = trackRef.current.getBoundingClientRect();
      const frac = zClamp((e.clientX - rect.left - Z_PAD) / (rect.width - 2 * Z_PAD), 0, 1);
      commitValue(Z_MIN + frac * (Z_MAX - Z_MIN), true);
      scheduleCollapse();
    } else {
      let target = valueRef.current;
      const nearStop = Z_STOPS.find((st) => Math.abs(target - st) < Z_SNAP_EPS);
      if (nearStop !== undefined) target = nearStop;
      else if (Math.abs(velRef.current) > Z_FLICK_VEL) {
        const dir = velRef.current > 0 ? 1 : -1;
        const candidates = Z_STOPS.filter((st) => (dir > 0 ? st > target : st < target));
        if (candidates.length) target = dir > 0 ? Math.min(...candidates) : Math.max(...candidates);
      }
      commitValue(target, true);
      scheduleCollapse();
    }
  };

  const idxPos = zIndexPos(value);
  const usableCompact = Z_COMPACT_W - Z_PAD * 2 - Z_THUMB;
  const usableExpanded = Z_EXPANDED_W - Z_PAD * 2;
  const compactThumbX = Z_PAD + (idxPos / (Z_STOPS.length - 1)) * usableCompact;
  const expandedThumbX = Z_PAD + ((value - Z_MIN) / (Z_MAX - Z_MIN)) * (usableExpanded - Z_THUMB_EXP);
  const isSnapped = Z_STOPS.some((st) => Math.abs(value - st) < Z_SNAP_EPS);

  const ticks = [];
  for (let t = Z_MIN; t <= Z_MAX + 0.001; t = zRound1(t + (t < 3 ? 0.1 : 0.5))) {
    const tv = zRound1(t);
    const isMajor = Z_MAJORS.some((m) => Math.abs(m - tv) < 0.01);
    const x = Z_PAD + ((tv - Z_MIN) / (Z_MAX - Z_MIN)) * usableExpanded;
    ticks.push(<div key={tv} style={{ position: "absolute", top: "50%", width: 1, background: TEXT, transform: "translate(-50%,-50%)", left: x, height: isMajor ? 10 : 5, opacity: isMajor ? 0.8 : 0.28 }} />);
    if (isMajor) ticks.push(<div key={`${tv}-l`} style={{ position: "absolute", bottom: 3, left: x, transform: "translateX(-50%)", fontFamily: FONT, fontSize: 6.5, color: TEXT, opacity: 0.4 }}>{tv === 0.5 ? ".5" : tv}</div>);
  }

  return (
    <div style={{
      position: "absolute", bottom: 132, left: "50%", transform: "translateX(-50%)", zIndex: 32,
      opacity: disabled ? 0 : 1, pointerEvents: disabled ? "none" : "auto", transition: "opacity 0.25s ease",
      display: "flex", flexDirection: "column", alignItems: "center", gap: 6,
    }}>
      <style>{`
        @keyframes zsSettlePop { 0%{filter:brightness(1)} 35%{filter:brightness(1.4)} 100%{filter:brightness(1)} }
        .zs-pulse { animation: zsSettlePop 300ms cubic-bezier(.22,1,.36,1); }
      `}</style>
      <div
        onClick={isMini ? onMiniTap : undefined}
        style={{
          position: "relative", display: "flex", alignItems: "center", justifyContent: "center",
          borderRadius: 999, background: "linear-gradient(155deg, rgba(28,28,31,0.9), rgba(3,3,4,0.96))",
          border: `1px solid ${BORDER}`,
          boxShadow: "inset 0 1px 0 rgba(255,255,255,0.06), 0 6px 16px rgba(0,0,0,0.5)",
          backdropFilter: "blur(16px)", WebkitBackdropFilter: "blur(16px)",
          transition: `width 0.26s ${SPRING}, height 0.26s ${SPRING}, transform 0.22s ${SPRING}`,
          transform: `scale(${pressed ? 0.97 : 1})`,
          width: isMini ? Z_MINI : (isExpanded ? Z_EXPANDED_W : Z_COMPACT_W) + Z_MM_W,
          height: isMini ? Z_MINI : (isExpanded ? Z_EXPANDED_H : Z_COMPACT_H),
          cursor: isMini ? "pointer" : "default", userSelect: "none", overflow: "hidden",
        }}
      >
        {isMini ? (
          <span style={{ fontFamily: FONT, fontSize: 11, fontWeight: 700, color: TEXT }}>{zFormat(value, false)}</span>
        ) : (
          <>
            <div
              ref={trackRef}
              onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}
              style={{
                position: "relative", flex: "0 0 auto", cursor: "pointer", touchAction: "none",
                width: isExpanded ? Z_EXPANDED_W : Z_COMPACT_W, height: isExpanded ? Z_EXPANDED_H : Z_COMPACT_H,
                transition: `width 0.26s ${SPRING}, height 0.26s ${SPRING}`,
              }}
            >
              {isExpanded && <div style={{ position: "absolute", inset: 0 }}>{ticks}</div>}
              {!isExpanded && Z_STOPS.map((st, i) => {
                const dist = Math.abs(idxPos - i);
                const opacity = Math.max(0.35, 1 - dist * 0.9);
                const scale = 1 + Math.max(0, 0.18 - dist * 0.18);
                const x = Z_PAD + Z_THUMB / 2 + (i / (Z_STOPS.length - 1)) * usableCompact;
                return (
                  <div key={st} style={{
                    position: "absolute", top: "50%", left: x, fontFamily: FONT, fontSize: 9, fontWeight: 600,
                    color: TEXT, transform: `translate(-50%,-50%) scale(${scale})`,
                    opacity: dist < 0.15 ? 0 : opacity, transition: "opacity 0.16s ease, transform 0.22s ease",
                  }}>{st === 0.5 ? ".5" : st}</div>
                );
              })}
              <div
                key={pulseTick}
                className={mountedRef.current ? "zs-pulse" : ""}
                style={{
                  position: "absolute", borderRadius: 999,
                  background: `linear-gradient(160deg, ${GOLD_HI} 0%, ${GOLD} 55%, #9C7C4A 100%)`,
                  display: "flex", alignItems: "center", justifyContent: "center",
                  boxShadow: isSnapped ? `0 0 0 3px rgba(201,169,110,0.28), 0 2px 10px rgba(201,169,110,0.55)` : "0 2px 8px rgba(201,169,110,0.4)",
                  left: isExpanded ? expandedThumbX : compactThumbX,
                  width: isExpanded ? Z_THUMB_EXP : Z_THUMB, height: isExpanded ? Z_EXPANDED_H - 2 * Z_PAD : Z_THUMB,
                  top: Z_PAD, transition: "box-shadow 0.16s ease",
                }}
              >
                {!isExpanded && <span style={{ fontFamily: FONT, fontSize: 9, fontWeight: 700, color: BG }}>{zFormat(value, false)}</span>}
              </div>
            </div>
            <div style={{ flex: "0 0 auto", width: Z_MM_W, height: "100%", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", borderLeft: `1px solid ${BORDER}`, lineHeight: 1 }}>
              <span key={`mm-${pulseTick}`} className={mountedRef.current ? "zs-pulse" : ""} style={{ fontFamily: FONT, fontSize: 11, fontWeight: 700, color: GOLD_HI }}>{zMm(value)}</span>
              <span style={{ fontFamily: FONT, fontSize: 6.5, fontWeight: 600, color: GOLD, letterSpacing: "0.06em", marginTop: 1 }}>MM</span>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function BottomIconButton({ icon: Icon, label, onClick, active, disabled }) {
  return (
    <button
      onClick={onClick} disabled={disabled}
      style={{
        display: "flex", flexDirection: "column", alignItems: "center", gap: 4,
        background: "transparent", border: "none", cursor: disabled ? "default" : "pointer",
        opacity: disabled ? 0.3 : 1, transition: "opacity 0.25s ease",
      }}
    >
      <div style={{
        width: 44, height: 44, borderRadius: "50%",
        display: "flex", alignItems: "center", justifyContent: "center",
        background: active ? `${GOLD}22` : "transparent",
        border: `1px solid ${active ? GOLD : "transparent"}`,
        transition: `all 0.28s ${SPRING}`,
      }}>
        <Icon size={19} color={active ? GOLD_HI : `${TEXT}cc`} strokeWidth={1.7} />
      </div>
      <span style={{ fontFamily: FONT, fontSize: 7, letterSpacing: "0.08em", color: active ? GOLD : MUTED2 }}>{label}</span>
    </button>
  );
}

/* ══════════════════════════ ANALOG ENGINE (film · effects · frame) ══════════════════════════ */
const G = { bg: "#030304", bar: "#0D0D11", surf: "#111115", surfUp: "#18181D",
  bdr: "#21212B", bdrDim: "#161620", bdrHi: "#2D2D3A", tx: "#EDE9E0", txSub: "#636060",
  gold: "#C9A96E", goldBg: "#C9A96E10", goldRim: "#C9A96E38" };
const MN = "'SF Mono','Menlo',monospace", SP = "cubic-bezier(0.34,1.56,0.64,1)", EO = "cubic-bezier(0.22,1,0.36,1)";

const FILMS = [{ id: "NONE", g: .08, tint: "#0a0a0a" }, { id: "CPM", g: .35, tint: "#1a1610" }, { id: "D", g: .55, tint: "#12140f" },
  { id: "FQSR", g: .4, tint: "#151016" }, { id: "FXN", g: .6, tint: "#160c0c" }, { id: "HLX", g: .25, tint: "#0d1216" }, { id: "KDK", g: .3, tint: "#161409" }];

const COLOR_PRESETS = [{ n: "ORANGE", v: "#FF8C00" }, { n: "ROSE", v: "#FF3B6B" }, { n: "BLUE", v: "#4FAAFF" }, { n: "RAINBOW", v: "rainbow" }, { n: "RANDOM", v: "random" }];
const RAINBOW_CSS = "linear-gradient(90deg,#FF3B6B,#FFB84F,#F4FF4F,#4FFF9E,#4FAAFF,#B84FFF)";
const RANDOM_SWATCH_CSS = "conic-gradient(from 0deg,#FF3B6B,#FFB84F,#F4FF4F,#4FFF9E,#4FAAFF,#B84FFF,#FF3B6B)";
function resolveColorValue(name) {
  if (name === "RAINBOW") return RAINBOW_CSS;
  if (name === "RANDOM") return `hsl(${Math.floor(Math.random() * 360)},75%,60%)`;
  const p = COLOR_PRESETS.find(c => c.n === name); return p ? p.v : name;
}

const FX = [
  { id: "GRAIN", label: "Grain", color: "#E8CD98", def: 42, controls: [{ kind: "choice", key: "texture", label: "TEXTURE", values: ["FINE", "COARSE"] }] },
  { id: "LEAK", label: "Light Leak", color: "#E8804A", def: 28, controls: [{ kind: "color", key: "color", label: "COLOR", values: COLOR_PRESETS }] },
  { id: "HALATION", label: "Halation", color: "#C9756E", def: 15 },
  { id: "BLOOM", label: "Bloom", color: "#D9B872", def: 20 },
  { id: "CHROMA", label: "Chroma Ab.", color: "#7EC9C4", def: 8 },
  { id: "VIGNETTE", label: "Vignette", color: "#8C7A5E", def: 35, controls: [{ kind: "choice", key: "shape", label: "SHAPE", values: ["ROUND", "OVAL"] }] },
  { id: "DUST", label: "Dust", color: "#B9AF9A", def: 12 },
  { id: "FADE", label: "Fade", color: "#9AA6B0", def: 18, controls: [{ kind: "choice", key: "tone", label: "TONE", values: ["WARM", "COOL"] }] },
  { id: "SHARPEN", label: "Sharpen", color: "#E0665A", def: 10 },
  { id: "SOLID", label: "Solid Color", color: "#C9A96E", def: 30, controls: [{ kind: "color", key: "color", label: "COLOR", values: COLOR_PRESETS }] },
  { id: "PRISM", label: "Prism", color: "#B08CFF", def: 26, controls: [{ kind: "color", key: "color", label: "COLOR", values: COLOR_PRESETS }, { kind: "choice", key: "type", label: "TYPE", values: ["LINEAR", "RADIAL", "DIAGONAL"] }] },
];
const DEFAULT_OPTS = {
  GRAIN: { texture: "FINE" }, LEAK: { color: "#FF8C00", colorName: "ORANGE" }, VIGNETTE: { shape: "ROUND" },
  FADE: { tone: "WARM" }, SOLID: { color: "#FF8C00", colorName: "ORANGE" }, PRISM: { color: "#4FAAFF", colorName: "BLUE", type: "LINEAR" },
};

const FRAMES = [
  { n: "POLAROID", mm: "79×79", ratio: 1, rare: false }, { n: "MAT", mm: "102×127", ratio: .8, rare: false },
  { n: "35MM", mm: "36×24", ratio: 1.5, rare: false }, { n: "MATTE", mm: "102×127", ratio: .8, rare: false },
  { n: "FLOAT", mm: "36×24", ratio: 1.5, rare: false }, { n: "POST", mm: "148×105", ratio: 1.41, rare: false },
  { n: "110", mm: "17×13", ratio: 1.31, rare: true }, { n: "MUSEUM", mm: "127×102", ratio: 1.25, rare: true },
  { n: "GILDED", mm: "79×79", ratio: 1, rare: true }, { n: "VELVET", mm: "102×127", ratio: .8, rare: true },
  { n: "CONTACT", mm: "36×24", ratio: 1.5, rare: true }, { n: "ARCHIVE", mm: "110×88", ratio: 1.25, rare: true },
];
const TABS = ["FILM", "EFFECTS", "FRAME"];

function Lbl({ children, gold, size }) { return <span style={{ fontFamily: MN, fontSize: size || 8.5, fontWeight: 700, letterSpacing: 1.8, color: gold ? G.gold : G.txSub, textTransform: "uppercase", userSelect: "none" }}>{children}</span>; }
function Toggle({ value, onChange }) {
  return (
    <div onClick={() => onChange(!value)} style={{ width: 40, height: 23, borderRadius: 12, cursor: "pointer", position: "relative", background: value ? G.gold : G.surfUp, border: "1px solid " + (value ? G.gold : G.bdr), transition: "background .22s " + SP }}>
      <div style={{ position: "absolute", top: 2, left: value ? 19 : 2, width: 17, height: 17, borderRadius: "50%", background: "#fff", boxShadow: "0 1px 4px rgba(0,0,0,.4)", transition: "left .22s " + SP }} />
    </div>
  );
}
function RangeSlider({ value, min, max, onChange, accent }) {
  const pct = ((value - min) / (max - min)) * 100;
  return <input type="range" min={min} max={max} value={value} onChange={e => onChange(+e.target.value)}
    className="fancyRange" style={{ width: "100%", display: "block", background: `linear-gradient(90deg, ${accent} ${pct}%, ${G.bdrHi} ${pct}%)` }} />;
}
function GrainCanvas({ tint, amount, vignette = 0, size, dim }) {
  const ref = useRef(null), raf = useRef(null);
  useEffect(() => {
    const cv = ref.current; if (!cv) return; const ctx = cv.getContext("2d");
    cv.width = size; cv.height = size;
    const draw = () => {
      ctx.fillStyle = tint; ctx.fillRect(0, 0, size, size);
      const n = size * size * .05 * amount;
      for (let i = 0; i < n; i++) { ctx.fillStyle = `rgba(255,255,255,${Math.random() * .35})`; ctx.fillRect(Math.random() * size, Math.random() * size, 1, 1); }
      if (vignette > 0) { const g = ctx.createRadialGradient(size / 2, size / 2, size * .2, size / 2, size / 2, size * .65); g.addColorStop(0, "rgba(0,0,0,0)"); g.addColorStop(1, `rgba(0,0,0,${vignette / 100 * .85})`); ctx.fillStyle = g; ctx.fillRect(0, 0, size, size); }
      raf.current = requestAnimationFrame(draw);
    };
    draw(); return () => cancelAnimationFrame(raf.current);
  }, [tint, amount, vignette, size]);
  return <canvas ref={ref} style={{ width: size, height: size, display: "block", filter: dim ? "brightness(.5) saturate(.4)" : "none", transition: "filter .2s" }} />;
}
function FilmStrip({ stock, setStock }) {
  const railRef = useRef(null), raf = useRef(null);
  const [centered, setCentered] = useState(stock);
  const measure = useCallback(() => {
    const rail = railRef.current; if (!rail) return;
    const mid = rail.scrollLeft + rail.clientWidth / 2;
    let best = 0, bestD = Infinity;
    Array.from(rail.children).forEach((el, i) => {
      const c = el.offsetLeft + el.clientWidth / 2, d = Math.abs(c - mid);
      el.style.transform = `scale(${Math.max(.72, 1 - d / 420)})`;
      el.style.opacity = Math.max(.35, 1 - d / 300);
      if (d < bestD) { bestD = d; best = i; }
    });
    if (FILMS[best] && FILMS[best].id !== centered) setCentered(FILMS[best].id);
  }, [centered]);
  useEffect(() => {
    const rail = railRef.current;
    const onScroll = () => { cancelAnimationFrame(raf.current); raf.current = requestAnimationFrame(measure); };
    rail.addEventListener("scroll", onScroll, { passive: true });
    measure();
    return () => rail.removeEventListener("scroll", onScroll);
  }, [measure]);
  useEffect(() => { if (centered !== stock) setStock(centered); }, [centered]); // eslint-disable-line
  const scrollTo = (i) => { const rail = railRef.current, el = rail.children[i]; rail.scrollTo({ left: el.offsetLeft - rail.clientWidth / 2 + el.clientWidth / 2, behavior: "smooth" }); };
  return (
    <div style={{ position: "relative", padding: "6px 0 2px" }}>
      <div style={sprocketRow} />
      <div ref={railRef} className="nosb" style={{ display: "flex", gap: 14, overflowX: "auto", scrollSnapType: "x mandatory", WebkitOverflowScrolling: "touch", padding: "0 calc(50% - 27px)", alignItems: "center" }}>
        {FILMS.map((f, i) => (
          <div key={f.id} onClick={() => scrollTo(i)} style={{ flexShrink: 0, width: 54, scrollSnapAlign: "center", scrollSnapStop: "always", display: "flex", flexDirection: "column", alignItems: "center", gap: 5, cursor: "pointer", transition: `transform 120ms ${EO}` }}>
            <div style={{ width: 54, height: 54, borderRadius: 8, border: `2px solid ${centered === f.id ? G.gold : G.bdrDim}`, boxShadow: centered === f.id ? `0 0 14px ${G.gold}40` : "none", overflow: "hidden", transition: "border-color .15s, box-shadow .15s" }}>
              <GrainCanvas tint={f.tint} amount={f.g} size={54} dim={centered !== f.id} />
            </div>
            <Lbl size={7} gold={centered === f.id}>{f.id}</Lbl>
          </div>
        ))}
      </div>
      <div style={{ ...sprocketRow, top: "auto", bottom: 0 }} />
      <div style={{ position: "absolute", left: "50%", top: 6, bottom: 6, width: 1, background: `${G.gold}30`, pointerEvents: "none" }} />
    </div>
  );
}
const sprocketRow = { position: "absolute", top: 0, left: 0, right: 0, height: 6, backgroundImage: `repeating-linear-gradient(90deg, ${G.bdrHi} 0 4px, transparent 4px 12px)`, opacity: .5 };

function Dial({ fx, value, on, onToggle, onChange, focused, onFocus }) {
  const svgRef = useRef(null), dragging = useRef(false);
  const SZ = 46, R = 17.5;
  const angleFromEvent = (clientX, clientY) => {
    const el = svgRef.current; if (!el) return null;
    const r = el.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    let deg = Math.atan2(clientY - cy, clientX - cx) * 180 / Math.PI + 90; if (deg < 0) deg += 360;
    const a = Math.min(300, deg > 300 ? 300 : deg);
    return Math.round((a / 300) * 100);
  };
  const start = (e) => {
    if (!on) { onToggle(); onFocus(); return; }
    onFocus(); dragging.current = true;
    const p = e.touches ? e.touches[0] : e;
    const v = angleFromEvent(p.clientX, p.clientY); if (v != null) onChange(v);
  };
  useEffect(() => {
    const mv = e => { if (!dragging.current) return; const p = e.touches ? e.touches[0] : e; const v = angleFromEvent(p.clientX, p.clientY); if (v != null) onChange(v); };
    const stop = () => dragging.current = false;
    window.addEventListener("mousemove", mv); window.addEventListener("mouseup", stop);
    window.addEventListener("touchmove", mv, { passive: true }); window.addEventListener("touchend", stop);
    return () => { window.removeEventListener("mousemove", mv); window.removeEventListener("mouseup", stop); window.removeEventListener("touchmove", mv); window.removeEventListener("touchend", stop); };
  }, [on]); // eslint-disable-line
  const circ = 2 * Math.PI * R, pct = value / 100;
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 5 }}>
      <svg ref={svgRef} width={SZ} height={SZ} viewBox={`0 0 ${SZ} ${SZ}`} onMouseDown={start} onTouchStart={start}
        onDoubleClick={onToggle} style={{ cursor: "grab", touchAction: "none", outline: focused && on ? `1px solid ${fx.color}55` : "none", borderRadius: "50%" }}>
        <circle cx={SZ / 2} cy={SZ / 2} r={R} fill={G.surfUp} stroke={G.bdrDim} strokeWidth="2" />
        <circle cx={SZ / 2} cy={SZ / 2} r={R} fill="none" stroke={on ? fx.color : G.bdrHi} strokeWidth="3" strokeLinecap="round"
          strokeDasharray={circ} strokeDashoffset={circ * (1 - (on ? pct : 0) * .833)} transform={`rotate(-240 ${SZ / 2} ${SZ / 2})`}
          style={{ transition: on ? "none" : "stroke-dashoffset 200ms, stroke 200ms" }} />
        <text x={SZ / 2} y={SZ / 2 + 4} textAnchor="middle" fontSize="9" fontFamily={MN} fontWeight="700" fill={on ? fx.color : G.txSub}>{on ? value : "—"}</text>
      </svg>
      <span onClick={() => { onToggle(); onFocus(); }} style={{ fontSize: 7, fontWeight: 700, letterSpacing: .6, color: on ? fx.color : G.txSub, textAlign: "center", cursor: "pointer", transition: "color .18s" }}>{fx.label}</span>
    </div>
  );
}
function ColorRow({ control, valueName, onChange }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 10 }}>
      <span style={{ fontSize: 7, fontWeight: 700, color: G.txSub, letterSpacing: 1.2, flexShrink: 0, width: 38 }}>{control.label}</span>
      <div style={{ display: "flex", gap: 8 }}>
        {control.values.map(c => {
          const isRainbow = c.n === "RAINBOW", isRandom = c.n === "RANDOM";
          const bg = isRainbow ? RAINBOW_CSS : isRandom ? RANDOM_SWATCH_CSS : c.v;
          const selected = valueName === c.n;
          return (
            <button key={c.n} onClick={() => onChange(c.n)} title={c.n} style={{ width: 22, height: 22, borderRadius: "50%", background: bg, border: "none", cursor: "pointer",
              outline: selected ? `2px solid ${G.tx}` : "2px solid transparent", outlineOffset: 2, display: "flex", alignItems: "center", justifyContent: "center",
              fontSize: 10, lineHeight: 1, transform: selected ? "scale(1.14)" : "scale(1)", transition: `transform 160ms ${SP}, outline-color .15s` }}>
              {isRandom ? "🎲" : null}
            </button>
          );
        })}
      </div>
    </div>
  );
}
function ChoiceRow({ control, value, onChange }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, marginTop: 10 }}>
      <span style={{ fontSize: 7, fontWeight: 700, color: G.txSub, letterSpacing: 1.2, flexShrink: 0, width: 38 }}>{control.label}</span>
      <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
        {control.values.map(v => (
          <button key={v} onClick={() => onChange(v)} style={{ padding: "5px 10px", borderRadius: 7, fontSize: 8, fontWeight: 700, letterSpacing: .7,
            background: value === v ? G.goldBg : G.surfUp, border: "1px solid " + (value === v ? G.goldRim : G.bdrDim), color: value === v ? G.gold : G.txSub,
            cursor: "pointer", transition: "all .15s" }}>{v}</button>
        ))}
      </div>
    </div>
  );
}
function EffectControlPanel({ fx, value, opts, onIntensity, onColorOpt, onChoiceOpt }) {
  if (!fx) return (
    <div style={{ margin: "6px 16px 8px", padding: "13px", border: `1px dashed ${G.bdrDim}`, borderRadius: 12, textAlign: "center" }}>
      <Lbl size={7.5}>TAP AN EFFECT BELOW TO FINE-TUNE IT</Lbl>
    </div>
  );
  const colorCtrl = (fx.controls || []).find(c => c.kind === "color");
  const liveColor = colorCtrl ? opts[colorCtrl.key] : null;
  const accent = (liveColor && liveColor[0] === "#") ? liveColor : fx.color;
  return (
    <div style={{ margin: "6px 16px 10px", padding: "12px 14px 10px", background: G.surf, border: `1px solid ${G.bdrHi}`, borderRadius: 14, animation: `fadeIn 160ms ${EO}` }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 9 }}>
        <Lbl gold size={9}>{fx.label}</Lbl>
        <span style={{ fontFamily: MN, fontSize: 12, fontWeight: 800, color: accent }}>{value}%</span>
      </div>
      <RangeSlider value={value} min={0} max={100} onChange={onIntensity} accent={accent} />
      {(fx.controls || []).map(c => c.kind === "color"
        ? <ColorRow key={c.key} control={c} valueName={opts[c.key + "Name"]} onChange={n => onColorOpt(c.key, n)} />
        : <ChoiceRow key={c.key} control={c} value={opts[c.key]} onChange={v => onChoiceOpt(c.key, v)} />
      )}
    </div>
  );
}
function FrameRow({ film, frame, setFrame, orient, setOrient, customW, customH, setCustomW, setCustomH }) {
  const railRef = useRef(null), raf = useRef(null);
  const list = [{ n: null, ratio: .8, mm: null, rare: false }, ...FRAMES, { n: "CUSTOM", ratio: customW / customH, mm: null, rare: false, custom: true }];
  const measure = useCallback(() => {
    const rail = railRef.current; if (!rail) return;
    const mid = rail.scrollLeft + rail.clientWidth / 2;
    let best = 0, bestD = Infinity;
    Array.from(rail.children).forEach((el, i) => {
      const c = el.offsetLeft + el.clientWidth / 2, ad = Math.abs(c - mid);
      el.style.transform = `scale(${Math.max(.8, 1 - ad / 340)})`;
      el.style.opacity = Math.max(.4, 1 - ad / 280);
      if (ad < bestD) { bestD = ad; best = i; }
    });
    const name = list[best].n;
    if (name !== frame) setFrame(name);
  }, [frame, setFrame, customW, customH]); // eslint-disable-line
  useEffect(() => {
    const rail = railRef.current;
    const onScroll = () => { cancelAnimationFrame(raf.current); raf.current = requestAnimationFrame(measure); };
    rail.addEventListener("scroll", onScroll, { passive: true });
    measure();
    return () => rail.removeEventListener("scroll", onScroll);
  }, [measure]);
  const scrollTo = i => { const rail = railRef.current, el = rail.children[i]; rail.scrollTo({ left: el.offsetLeft - rail.clientWidth / 2 + el.clientWidth / 2, behavior: "smooth" }); };
  const tint = FILMS.find(x => x.id === film).tint;
  const active = list.find(x => x.n === frame) || list[0];
  const H = 150, ratio = orient === "landscape" ? 1 / active.ratio : active.ratio;
  const boxW = orient === "landscape" ? H : Math.round(H * ratio), boxH = orient === "landscape" ? Math.round(H / ratio) : H;
  return (
    <>
      <div ref={railRef} className="nosb" style={{ display: "flex", gap: 16, overflowX: "auto", scrollSnapType: "x mandatory", WebkitOverflowScrolling: "touch", padding: "8px calc(50% - 34px) 12px", alignItems: "center" }}>
        {list.map((f, i) => (
          <div key={f.n || "none"} onClick={() => scrollTo(i)} style={{ flexShrink: 0, width: 68, scrollSnapAlign: "center", scrollSnapStop: "always", cursor: "pointer", transition: `transform 120ms ${EO}` }}>
            <div style={{ width: 68, height: 68, borderRadius: 8, background: f.n ? "#efeeec" : "transparent", border: f.n ? (f.custom ? `1px dashed ${G.gold}` : `1px solid ${G.bdrHi}`) : `1px dashed ${G.bdrHi}`, display: "flex", alignItems: "center", justifyContent: "center" }}>
              {f.n ? <div style={{ width: 44, height: 44, aspectRatio: f.ratio, background: f.custom ? `repeating-linear-gradient(135deg,${tint} 0 4px,#1c1c1c 4px 8px)` : `linear-gradient(160deg,${tint},#1c1c1c)`, borderRadius: 1, maxHeight: 44 }} /> : <span style={{ fontSize: 14, color: G.txSub }}>—</span>}
            </div>
            <div style={{ textAlign: "center", marginTop: 5 }}><Lbl size={6.5} gold={frame === f.n}>{f.n || "NONE"}</Lbl></div>
          </div>
        ))}
      </div>
      <div style={{ display: "flex", justifyContent: "center", padding: "2px 0 4px" }}>
        <div style={{ width: boxW, height: boxH, borderRadius: 6, background: active.n ? "#efeeec" : "transparent", border: active.n ? "none" : `1px dashed ${G.bdrHi}`, display: "flex", flexDirection: "column", boxShadow: active.n ? "0 10px 22px rgba(0,0,0,.45)" : "none", transition: `width 260ms ${EO}, height 260ms ${EO}` }}>
          <div style={{ flex: 1, margin: active.n ? 10 : 0, borderRadius: active.n ? 2 : 8, background: `linear-gradient(160deg,${tint},#1c1c1c)`, position: "relative" }}>
            {active.rare && <div style={{ position: "absolute", top: 4, right: 4, width: 5, height: 5, borderRadius: "50%", background: G.gold }} />}
          </div>
        </div>
      </div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", gap: 14, padding: "2px 0 8px" }}>
        <span style={{ fontSize: 8, color: G.txSub, letterSpacing: .8 }}>{active.custom ? `${customW}×${customH}MM` : active.mm ? `${active.mm}MM` : "NO FRAME"}</span>
        {active.n && <button onClick={() => setOrient(o => o === "portrait" ? "landscape" : "portrait")} style={{ display: "flex", alignItems: "center", gap: 5, background: G.surfUp, border: `1px solid ${G.bdr}`, borderRadius: 8, padding: "5px 10px", cursor: "pointer", color: G.txSub, fontSize: 8, fontWeight: 700, letterSpacing: .6 }}>
          <span style={{ display: "inline-block", transform: orient === "landscape" ? "rotate(90deg)" : "none", transition: `transform 260ms ${EO}` }}>⤾</span>
          ROTATE
        </button>}
      </div>
    </>
  );
}

function AnalogSheet({ onClose = () => {}, onApply = () => {} }) {
  const [tab, setTab] = useState("FILM");
  const [film, setFilm] = useState("NONE");
  const [frame, setFrame] = useState(null);
  const [orient, setOrient] = useState("portrait");
  const [customW, setCustomW] = useState(36);
  const [customH, setCustomH] = useState(24);
  const [active, setActive] = useState(["GRAIN", "LEAK"]);
  const [vals, setVals] = useState(Object.fromEntries(FX.map(f => [f.id, f.def])));
  const [opts, setOpts] = useState(DEFAULT_OPTS);
  const [focus, setFocus] = useState("GRAIN");
  const [dateStamp, setDateStamp] = useState(false);
  const [dragY, setDragY] = useState(0);
  const [closing, setClosing] = useState(false);
  const drag = useRef({ y: 0, on: false });

  const dismiss = () => { setClosing(true); setTimeout(onClose, 240); };
  const down = e => { drag.current = { y: e.touches ? e.touches[0].clientY : e.clientY, on: true }; };
  const move = useCallback(e => { if (!drag.current.on) return; const y = e.touches ? e.touches[0].clientY : e.clientY; setDragY(Math.max(0, y - drag.current.y)); }, []);
  const up = useCallback(() => { if (!drag.current.on) return; drag.current.on = false; if (dragY > 100) dismiss(); else setDragY(0); }, [dragY]);
  useEffect(() => {
    window.addEventListener("mousemove", move); window.addEventListener("mouseup", up);
    window.addEventListener("touchmove", move, { passive: true }); window.addEventListener("touchend", up);
    return () => { window.removeEventListener("mousemove", move); window.removeEventListener("mouseup", up); window.removeEventListener("touchmove", move); window.removeEventListener("touchend", up); };
  }, [move, up]);

  const toggleFx = id => { setActive(a => a.includes(id) ? a.filter(x => x !== id) : [...a, id]); setFocus(id); };
  const tabIdx = TABS.indexOf(tab);
  const focusFx = FX.find(x => x.id === focus);
  const focusOn = active.includes(focus);
  const setIntensity = v => setVals(p => ({ ...p, [focus]: v }));
  const setColorOpt = (key, name) => setOpts(p => ({ ...p, [focus]: { ...p[focus], [key]: resolveColorValue(name), [key + "Name"]: name } }));
  const setChoiceOpt = (key, val) => setOpts(p => ({ ...p, [focus]: { ...p[focus], [key]: val } }));

  return (
    <div style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,.5)", display: "flex", alignItems: "flex-end", zIndex: 200, fontFamily: MN }}>
      <div style={{ width: "100%", background: G.bar, borderRadius: "26px 26px 0 0", maxHeight: "93%", display: "flex", flexDirection: "column",
        transform: closing ? "translateY(100%)" : `translateY(${dragY}px)`, opacity: closing ? 0 : 1,
        transition: drag.current.on ? "none" : `transform ${closing ? 240 : 380}ms ${closing ? EO : SP}, opacity 240ms ${EO}` }}>
        <div onMouseDown={down} onTouchStart={down} style={{ display: "flex", justifyContent: "center", padding: "10px 0 2px", cursor: "grab", touchAction: "none" }}>
          <div style={{ width: 32, height: 3, borderRadius: 2, background: G.bdrHi }} />
        </div>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "6px 16px 10px" }}>
          <Lbl gold size={12}>ANALOG ENGINE</Lbl>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            {active.length > 0 && <span style={{ fontSize: 7, fontWeight: 800, color: G.gold, background: G.goldBg, border: `1px solid ${G.goldRim}`, borderRadius: 4, padding: "2px 6px" }}>{active.length} ON</span>}
            <button onClick={dismiss} style={{ width: 26, height: 26, borderRadius: "50%", background: G.surfUp, border: "1px solid " + G.bdr, color: G.txSub, fontSize: 15, cursor: "pointer" }}>×</button>
          </div>
        </div>
        <div style={{ position: "relative", display: "flex", padding: "0 16px", borderBottom: `1px solid ${G.bdrDim}` }}>
          {TABS.map(t => (
            <button key={t} onClick={() => setTab(t)} style={{ flex: 1, background: "transparent", border: "none", padding: "8px 0", fontSize: 10, fontWeight: 700, letterSpacing: 1.6, color: tab === t ? G.gold : G.txSub, cursor: "pointer", transition: "color .2s" }}>{t}</button>
          ))}
          <div style={{ position: "absolute", bottom: -1, left: 16, height: 2, width: `calc((100% - 32px)/3)`, background: G.gold, borderRadius: 1, transform: `translateX(${tabIdx * 100}%)`, transition: `transform 280ms ${EO}` }} />
        </div>
        <div style={{ overflowY: "auto", flex: 1, padding: "4px 0 14px" }}>
          {tab === "FILM" && <FilmStrip stock={film} setStock={setFilm} />}
          {tab === "EFFECTS" && (
            <>
              <EffectControlPanel fx={focusOn ? focusFx : null} value={vals[focus]} opts={opts[focus] || {}}
                onIntensity={setIntensity} onColorOpt={setColorOpt} onChoiceOpt={setChoiceOpt} />
              <div style={{ display: "grid", gridTemplateColumns: "repeat(4,1fr)", gap: "12px 4px", padding: "2px 16px 4px" }}>
                {FX.map(fx => (
                  <Dial key={fx.id} fx={fx} value={vals[fx.id]} on={active.includes(fx.id)} focused={focus === fx.id}
                    onToggle={() => toggleFx(fx.id)} onFocus={() => setFocus(fx.id)} onChange={v => setVals(p => ({ ...p, [fx.id]: v }))} />
                ))}
              </div>
            </>
          )}
          {tab === "FRAME" && (
            <>
              <FrameRow film={film} frame={frame} setFrame={setFrame} orient={orient} setOrient={setOrient}
                customW={customW} customH={customH} setCustomW={setCustomW} setCustomH={setCustomH} />
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "10px 16px", borderTop: `1px solid ${G.bdrDim}`, margin: "0 16px" }}>
                <span style={{ fontSize: 9.5, fontWeight: 700, letterSpacing: 1.5, color: G.txSub }}>DATE STAMP</span>
                <Toggle value={dateStamp} onChange={setDateStamp} />
              </div>
            </>
          )}
        </div>
        <div style={{ display: "flex", gap: 10, padding: "10px 16px 22px" }}>
          <button style={{ flex: 1, padding: "13px 0", borderRadius: 14, border: `1px solid ${G.gold}`, background: G.goldBg, color: G.gold, fontSize: 11, fontWeight: 700, letterSpacing: 1, cursor: "pointer" }}>EDIT FRAME</button>
          <button onClick={() => onApply({ film, frame, orient, customFrame: frame === "CUSTOM" ? { w: customW, h: customH } : null, active, vals, opts, dateStamp })} style={{ flex: 1, padding: "13px 0", borderRadius: 14, border: "none", background: G.gold, color: "#1a1408", fontSize: 11, fontWeight: 800, letterSpacing: 1, cursor: "pointer", boxShadow: `0 4px 16px ${G.gold}35` }}>APPLY</button>
        </div>
      </div>
      <style>{`
        @keyframes fadeIn{from{opacity:0;transform:translateY(-4px)}to{opacity:1;transform:translateY(0)}}
        .nosb::-webkit-scrollbar{display:none}.nosb{-ms-overflow-style:none;scrollbar-width:none}
        .fancyRange{-webkit-appearance:none;appearance:none;height:4px;border-radius:2px;outline:none;cursor:pointer}
        .fancyRange::-webkit-slider-thumb{-webkit-appearance:none;appearance:none;width:16px;height:16px;border-radius:50%;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.5);cursor:pointer;margin-top:0}
        .fancyRange::-moz-range-thumb{width:16px;height:16px;border-radius:50%;background:#fff;border:none;box-shadow:0 1px 4px rgba(0,0,0,.5);cursor:pointer}
        .fancyRange::-webkit-slider-runnable-track{height:4px;border-radius:2px;background:transparent}
        .fancyRange::-moz-range-track{height:4px;border-radius:2px;background:transparent}
      `}</style>
    </div>
  );
}

/* ══════════════════════ PRO TICK WHEEL (real precision slider engine) ══════════════════════ */
const SCALES = {
  ISO: { coarse: [100, 200, 400, 800, 1600, 3200, 6400, 12800], fine: [100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800], label: "ISO", unit: "" },
  SS: { coarse: ["1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1s", "2s"], fine: ["1/4000", "1/3200", "1/2500", "1/2000", "1/1600", "1/1250", "1/1000", "1/800", "1/640", "1/500", "1/400", "1/320", "1/250", "1/200", "1/160", "1/125", "1/100", "1/80", "1/60", "1/50", "1/40", "1/30", "1/25", "1/20", "1/15", "1/13", "1/10", "1/8", "1/6", "1/5", "1/4", "1/3", "1/2.5", "1/2", "1/1.6", "1/1.3", "1s", "1.3s", "1.6s", "2s"], label: "SS", unit: "" },
  WB: { coarse: [2500, 3200, 4000, 5000, 5500, 6500, 7500, 10000], fine: Array.from({ length: 31 }, (_, i) => 2500 + i * 250), label: "WB", unit: "K" },
  FOCUS: { coarse: [0, 0.1, 0.2, 0.3, 0.5, 0.7, 1.0], fine: Array.from({ length: 21 }, (_, i) => parseFloat((i * 0.05).toFixed(2))), label: "MF", unit: "m" },
  EV: { coarse: [-3, -2, -1, 0, 1, 2, 3], fine: Array.from({ length: 25 }, (_, i) => parseFloat((-3 + i * 0.25).toFixed(2))), label: "EV", unit: "", signed: true },
};
const GROOVE_LO = "#000000", GROOVE_HI = "#17171D", MUTED = "#28282E";
const TW = 240, H = 52, DPR = typeof window !== "undefined" ? window.devicePixelRatio || 1 : 1;
const COARSE_PX = 42, FINE_PX = 24, FINE_SUBS = 4, FLASH_MS = 220;
const reducedMotion = typeof window !== "undefined" && window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

function drawTicks(ctx, values, floatIdx, mode, coarseSet, flash, t = performance.now()) {
  const cw = ctx.canvas.width, ch = ctx.canvas.height;
  ctx.clearRect(0, 0, cw, ch);
  const cx = cw / 2;
  const step = (mode === "coarse" ? COARSE_PX : FINE_PX) * DPR;
  const centerIdx = Math.round(floatIdx);
  const isFine = mode === "fine";
  if (isFine) {
    const subStep = step / (FINE_SUBS + 1);
    const subSpan = Math.ceil((cw / subStep) / 2) + 5;
    const baseF = Math.floor(floatIdx);
    for (let si = baseF - subSpan; si <= baseF + subSpan; si++) {
      for (let k = 1; k <= FINE_SUBS; k++) {
        if (si < 0 || si + 1 > values.length - 1) continue;
        const sx = cx + (si + k / (FINE_SUBS + 1) - floatIdx) * step;
        if (sx < 0 || sx > cw) continue;
        const dist = Math.abs(sx - cx) / cx;
        const alpha = Math.max(0, Math.cos(dist * Math.PI * 0.5)) * 0.38;
        if (alpha < 0.02) continue;
        ctx.fillStyle = `rgba(65,63,75,${alpha.toFixed(2)})`;
        ctx.fillRect(sx - 0.5 * DPR, ch * 0.42, 0.75 * DPR, 7 * DPR);
      }
    }
  }
  const span = Math.ceil((cw / step) / 2) + 3;
  const flashAge = flash && flash.idx != null ? performance.now() - flash.start : Infinity;
  const flashActive = !reducedMotion && flashAge < FLASH_MS;
  const flashK = flashActive ? Math.pow(1 - flashAge / FLASH_MS, 2) : 0;
  for (let vi = Math.max(0, centerIdx - span); vi <= Math.min(values.length - 1, centerIdx + span); vi++) {
    const sx = cx + (vi - floatIdx) * step;
    if (sx < -4 || sx > cw + 4) continue;
    const dist = Math.abs(sx - cx) / cx;
    const alpha = Math.max(0, Math.cos(dist * Math.PI * 0.5));
    if (alpha < 0.01) continue;
    const isCenter = vi === centerIdx;
    const isMajor = mode === "coarse" || coarseSet.has(String(values[vi]));
    const isFlash = flashActive && vi === flash.idx;
    const shimmer = reducedMotion ? 1 : 1 + 0.16 * Math.sin(t / 1100 + vi * 0.55);
    let th, topY, color, tw;
    if (isCenter) { th = 32 * DPR; topY = ch * 0.06; tw = 2 * DPR; color = GOLD; }
    else if (isMajor) { th = 20 * DPR * Math.max(0.85, Math.min(1.1, shimmer)); topY = ch * 0.2; tw = 1.2 * DPR; color = `rgba(180,175,165,${(alpha * 0.7 * shimmer).toFixed(2)})`; }
    else { th = 11 * DPR * Math.max(0.8, Math.min(1.15, shimmer)); topY = ch * 0.36; tw = 0.9 * DPR; color = `rgba(72,70,82,${(alpha * 0.8 * shimmer).toFixed(2)})`; }
    if (!isCenter) topY = ch * (isMajor ? 0.2 : 0.36) + ((isMajor ? 20 * DPR : 11 * DPR) - th) / 2;
    if (isFlash) {
      th = th * (1 + 0.4 * flashK); tw = tw * (1 + 0.8 * flashK);
      color = isCenter ? GOLD_HI : `rgba(201,169,110,${(0.35 * flashK + alpha * 0.3).toFixed(2)})`;
      topY -= (th - (isCenter ? 32 * DPR : isMajor ? 20 * DPR : 11 * DPR)) * 0.5;
    }
    ctx.fillStyle = color;
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(sx - tw / 2, topY, tw, th, 1); else ctx.rect(sx - tw / 2, topY, tw, th);
    ctx.fill();
    if (isCenter && !reducedMotion) {
      ctx.save(); ctx.shadowColor = GOLD; ctx.shadowBlur = 6 * DPR; ctx.globalAlpha = 0.5;
      ctx.fillRect(sx - tw / 2, topY, tw, th); ctx.restore();
    }
  }
}
function FlapNumber({ value, isFine }) {
  const [shown, setShown] = useState(value);
  const [animKey, setAnimKey] = useState(0);
  const timerRef = useRef(null);
  useEffect(() => {
    if (value === shown) return;
    setAnimKey(k => k + 1);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setShown(value), 60);
    return () => clearTimeout(timerRef.current);
  }, [value]);
  const str = String(shown ?? "—");
  const fs = str.length > 5 ? 15 : str.length > 4 ? 18 : str.length > 3 ? 21 : 26;
  return (
    <div key={animKey} style={{
      fontFamily: FONT, fontSize: fs, fontWeight: 300, fontVariantNumeric: "tabular-nums",
      color: isFine ? GOLD : TEXT, letterSpacing: "-0.04em", lineHeight: 1, textAlign: "right",
      textShadow: isFine ? `0 0 14px ${GOLD}55` : "none",
      animation: reducedMotion ? "none" : "flapIn 0.16s cubic-bezier(0.22,1,0.36,1) both",
      transition: "color 0.25s, text-shadow 0.3s",
    }}>{shown}</div>
  );
}
function RangeRail({ frac, isFine }) {
  return (
    <div style={{ position: "relative", width: TW, height: 3, flexShrink: 0, borderRadius: 2, overflow: "hidden", background: GROOVE_LO, boxShadow: `inset 0 1px 1px #000, inset 0 -1px 0 ${GROOVE_HI}` }}>
      <div style={{ position: "absolute", top: 0, bottom: 0, left: 0, width: `${Math.max(1.5, frac * 100)}%`, background: `linear-gradient(to right, ${GOLD}22, ${isFine ? GOLD_HI : GOLD}99)`, transition: reducedMotion ? "none" : "width 0.12s linear, background 0.3s" }} />
      <div style={{ position: "absolute", top: "50%", left: `${frac * 100}%`, width: 5, height: 5, borderRadius: "50%", background: isFine ? GOLD_HI : GOLD, boxShadow: `0 0 6px 1px ${GOLD}aa`, transform: "translate(-50%,-50%)", transition: reducedMotion ? "none" : "left 0.12s linear" }} />
    </div>
  );
}
function TickWheel({ scaleKey, defaultIndex = 0, active = true }) {
  const scale = SCALES[scaleKey];
  const canvasRef = useRef(null);
  const stateRef = useRef({ mode: "coarse", floatIdx: defaultIndex, dragging: false, lastX: 0, lastT: 0, velBuf: [], velHistory: [], flash: { idx: null, start: 0 }, lastCenter: Math.round(defaultIndex) });
  const [display, setDisplay] = useState({ val: scale.coarse[defaultIndex], mode: "coarse" });
  const [pressed, setPressed] = useState(false);
  const modeTimer = useRef(null), rafRef = useRef(null), idleRaf = useRef(null), lastSwitchRef = useRef(0);
  const coarseSet = useRef(new Set(scale.coarse.map(String)));
  const getVals = useCallback(m => m === "coarse" ? scale.coarse : scale.fine, [scale]);
  const maybeFlash = (s, vals) => { const c = Math.round(s.floatIdx); if (c !== s.lastCenter) { s.lastCenter = c; s.flash = { idx: c, start: performance.now() }; } };
  const redraw = useCallback(() => { const c = canvasRef.current; if (!c) return; const s = stateRef.current; drawTicks(c.getContext("2d"), getVals(s.mode), s.floatIdx, s.mode, coarseSet.current, s.flash); }, [getVals]);
  useEffect(() => { const c = canvasRef.current; if (!c) return; c.width = TW * DPR; c.height = H * DPR; c.style.width = TW + "px"; c.style.height = H + "px"; redraw(); }, [redraw]);
  useEffect(() => { redraw(); }, [redraw]);
  useEffect(() => {
    // bug fix: with all five PRO wheels now kept mounted (see ProPanel), only the
    // one currently on screen should spend CPU on its idle-breathing render loop.
    if (reducedMotion || !active) return;
    let alive = true;
    const loop = () => {
      if (!alive) return;
      const s = stateRef.current;
      if (!s.dragging && !rafRef.current) { const c = canvasRef.current; if (c) drawTicks(c.getContext("2d"), getVals(s.mode), s.floatIdx, s.mode, coarseSet.current, s.flash, performance.now()); }
      idleRaf.current = requestAnimationFrame(loop);
    };
    idleRaf.current = requestAnimationFrame(loop);
    return () => { alive = false; cancelAnimationFrame(idleRaf.current); };
  }, [getVals, active]);
  const switchMode = useCallback(newMode => {
    const s = stateRef.current; if (s.mode === newMode) return;
    const now = Date.now(); if (now - lastSwitchRef.current < 600) return; lastSwitchRef.current = now;
    const oldVals = getVals(s.mode), newVals = getVals(newMode);
    const curVal = oldVals[Math.max(0, Math.min(Math.round(s.floatIdx), oldVals.length - 1))];
    let best = 0, bestD = Infinity;
    newVals.forEach((v, i) => { const d = typeof v === "number" && typeof curVal === "number" ? Math.abs(v - curVal) : v === curVal ? 0 : Infinity; if (d < bestD) { bestD = d; best = i; } });
    s.mode = newMode; s.floatIdx = best; s.lastCenter = best;
    setDisplay({ val: newVals[best], mode: newMode }); redraw();
  }, [getVals, redraw]);
  const onStart = useCallback(clientX => { const s = stateRef.current; s.dragging = true; s.lastX = clientX; s.lastT = Date.now(); s.velBuf = []; s.velHistory = []; setPressed(true); cancelAnimationFrame(rafRef.current); }, []);
  const onMove = useCallback(clientX => {
    const s = stateRef.current; if (!s.dragging) return;
    const dx = clientX - s.lastX, now = Date.now(), dt = Math.max(now - s.lastT, 1);
    const spd = Math.abs(dx) / dt; s.velBuf.push(spd); if (s.velBuf.length > 8) s.velBuf.shift();
    const avg = s.velBuf.reduce((a, b) => a + b, 0) / s.velBuf.length;
    s.velHistory.push(dx / dt); if (s.velHistory.length > 6) s.velHistory.shift();
    clearTimeout(modeTimer.current);
    if (avg > 2.4 && s.mode === "fine") modeTimer.current = setTimeout(() => switchMode("coarse"), 160);
    if (avg < 0.15 && s.mode === "coarse") modeTimer.current = setTimeout(() => switchMode("fine"), 400);
    const vals = getVals(s.mode), stepPx = s.mode === "coarse" ? COARSE_PX : FINE_PX;
    const unclamped = s.floatIdx - dx / stepPx;
    s.floatIdx = Math.max(0, Math.min(unclamped, vals.length - 1));
    s.lastX = clientX; s.lastT = now;
    maybeFlash(s, vals);
    const c = canvasRef.current; if (!c) return;
    drawTicks(c.getContext("2d"), vals, s.floatIdx, s.mode, coarseSet.current, s.flash);
    setDisplay({ val: vals[Math.round(s.floatIdx)], mode: s.mode });
    cancelAnimationFrame(rafRef.current);
  }, [getVals, switchMode]);
  const onEnd = useCallback(() => {
    const s = stateRef.current; if (!s.dragging) return;
    s.dragging = false; setPressed(false); clearTimeout(modeTimer.current);
    const vals = getVals(s.mode), stepPx = s.mode === "coarse" ? COARSE_PX : FINE_PX;
    const snapTo = to => {
      const from = s.floatIdx;
      if (Math.abs(to - from) < 0.0005) { s.floatIdx = to; rafRef.current = null; redraw(); setDisplay({ val: vals[to], mode: s.mode }); return; }
      const dur = 340, t0 = performance.now();
      const ease = x => { const c1 = 1.4, c3 = c1 + 1; return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2); };
      const anim = now => {
        const t = Math.min((now - t0) / dur, 1);
        s.floatIdx = from + (to - from) * ease(t);
        maybeFlash(s, vals);
        const c = canvasRef.current;
        if (c) drawTicks(c.getContext("2d"), vals, s.floatIdx, s.mode, coarseSet.current, s.flash);
        setDisplay({ val: vals[Math.round(s.floatIdx)], mode: s.mode });
        if (t < 1) { rafRef.current = requestAnimationFrame(anim); }
        else { s.floatIdx = to; s.lastCenter = to; rafRef.current = null; redraw(); setDisplay({ val: vals[to], mode: s.mode }); }
      };
      rafRef.current = requestAnimationFrame(anim);
    };
    const hist = s.velHistory.slice(-5);
    const relVelPx = hist.length > 0 ? hist.reduce((sum, v, i) => sum + v * (i + 1), 0) / hist.reduce((sum, _, i) => sum + (i + 1), 0) : 0;
    let flingVel = -relVelPx / stepPx;
    const FRICTION = 0.86, MIN_VEL = 0.0025;
    if (Math.abs(flingVel) < MIN_VEL) { snapTo(Math.round(s.floatIdx)); return; }
    let last = performance.now();
    const fling = now => {
      const dt = Math.min(now - last, 32); last = now;
      s.floatIdx += flingVel * dt; s.floatIdx = Math.max(0, Math.min(s.floatIdx, vals.length - 1));
      flingVel *= Math.pow(FRICTION, dt / 16.67);
      maybeFlash(s, vals);
      const c = canvasRef.current; if (c) drawTicks(c.getContext("2d"), vals, s.floatIdx, s.mode, coarseSet.current, s.flash);
      setDisplay({ val: vals[Math.round(s.floatIdx)], mode: s.mode });
      const hitWall = s.floatIdx <= 0 || s.floatIdx >= vals.length - 1;
      if (Math.abs(flingVel) < MIN_VEL || hitWall) { snapTo(Math.round(s.floatIdx)); } else { rafRef.current = requestAnimationFrame(fling); }
    };
    rafRef.current = requestAnimationFrame(fling);
  }, [getVals, redraw]);
  useEffect(() => { const mm = e => onMove(e.clientX); window.addEventListener("mousemove", mm); window.addEventListener("mouseup", onEnd); return () => { window.removeEventListener("mousemove", mm); window.removeEventListener("mouseup", onEnd); }; }, [onMove, onEnd]);
  useEffect(() => {
    const el = canvasRef.current; if (!el) return;
    const tm = e => { e.preventDefault(); onMove(e.touches[0].clientX); };
    el.addEventListener("touchmove", tm, { passive: false });
    el.addEventListener("touchend", onEnd);
    return () => { el.removeEventListener("touchmove", tm); el.removeEventListener("touchend", onEnd); };
  }, [onMove, onEnd]);
  const signedNum = typeof display.val === "number" && scale.signed ? (display.val > 0 ? `+${display.val.toFixed(1)}` : display.val.toFixed(1)) : null;
  const dispVal = signedNum ?? (typeof display.val === "number" && scale.unit ? `${display.val}${scale.unit}` : String(display.val ?? "—"));
  const isFine = display.mode === "fine";
  const vals = getVals(display.mode);
  const frac = vals.length > 1 ? Math.round(stateRef.current.floatIdx) / (vals.length - 1) : 0;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8, userSelect: "none", WebkitUserSelect: "none" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 0 }}>
        <div style={{ position: "relative", width: TW, height: H, overflow: "hidden", flexShrink: 0, borderRadius: 8, boxShadow: `inset 0 2px 5px #000000cc, inset 0 -1px 0 ${GROOVE_HI}`, background: BG, transform: pressed && !reducedMotion ? "scale(0.988)" : "scale(1)", transition: `transform 0.16s ${SPRING}` }}>
          <div style={{ position: "absolute", inset: 0, zIndex: 2, pointerEvents: "none", background: `linear-gradient(to right, ${BG} 0%, transparent 18%, transparent 82%, ${BG} 100%)` }} />
          <div style={{ position: "absolute", left: "50%", top: 0, zIndex: 3, pointerEvents: "none", transform: "translateX(-50%)", width: 1, height: H,
            background: `linear-gradient(to bottom, transparent 0%, ${GOLD}bb 6%, ${GOLD} 18%, ${GOLD} 82%, ${GOLD}bb 94%, transparent 100%)`,
            animation: reducedMotion ? "none" : (pressed ? "needleAwake 0.9s ease-in-out infinite" : "needleBreath 2.8s ease-in-out infinite") }} />
          <canvas ref={canvasRef} onMouseDown={e => { e.preventDefault(); onStart(e.clientX); }} onTouchStart={e => onStart(e.touches[0].clientX)} style={{ display: "block", cursor: "ew-resize", touchAction: "none" }} />
        </div>
        <div style={{ width: 1, height: 32, flexShrink: 0, marginLeft: 14, marginRight: 14, background: `linear-gradient(to bottom, transparent, ${GOLD}55, transparent)` }} />
        <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 3, minWidth: 64, flexShrink: 0 }}>
          <span style={{ fontFamily: FONT, fontSize: 7.5, letterSpacing: "0.2em", color: isFine ? `${GOLD}dd` : MUTED2, textTransform: "uppercase", transition: "color 0.3s" }}>{scale.label}{isFine ? " ·fine" : ""}</span>
          <FlapNumber value={dispVal} isFine={isFine} />
          <div style={{ display: "flex", gap: 3, alignItems: "center", height: 6, opacity: isFine ? 1 : 0, transform: isFine ? "none" : "translateX(4px)", transition: "opacity 0.3s, transform 0.3s" }}>
            {[0, 1, 2].map(i => <div key={i} style={{ width: 3, height: 3, borderRadius: "50%", background: i === 1 ? GOLD : `${GOLD}44`, boxShadow: i === 1 ? `0 0 4px ${GOLD}` : "none" }} />)}
          </div>
        </div>
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
        <RangeRail frac={frac} isFine={isFine} />
      </div>
      <style>{`
        @keyframes needleBreath { 0%,100%{opacity:0.65; box-shadow:0 0 3px 0 #C9A96E22} 50%{opacity:1; box-shadow:0 0 10px 2px #C9A96E55, 0 0 20px 6px #C9A96E14} }
        @keyframes needleAwake { 0%,100%{opacity:0.9} 50%{opacity:1} }
        @keyframes flapIn { 0%{opacity:0; transform:translateY(6px) scaleY(0.8)} 100%{opacity:1; transform:translateY(0) scaleY(1)} }
      `}</style>
    </div>
  );
}

// ── PRO panel — manual ISO / shutter / WB / focus + EV, docked above the bottom bar ──
// Each parameter (ISO/SS/WB/FOCUS) carries its own independent AUTO/MANUAL state.
// EV (exposure compensation) is always live — it has no AUTO mode — so it's pinned
// as its own tab at the far right, set off from the others by a divider.
const PRO_TABS = ["ISO", "SS", "WB", "FOCUS"];
const PRO_DEF = { ISO: 0, SS: 6, WB: 3, FOCUS: 0, EV: 3 };

function ProPanel({ open }) {
  const [autoMap, setAutoMap] = useState({ ISO: true, SS: true, WB: true, FOCUS: true });
  const [active, setActive] = useState("ISO");
  const isEv = active === "EV";
  const toggleAuto = () => setAutoMap(m => ({ ...m, [active]: !m[active] }));

  return (
    <div style={{
      position: "absolute", left: 0, right: 0, bottom: 112, zIndex: 45,
      transform: `translateY(${open ? "0" : "16px"})`, opacity: open ? 1 : 0,
      pointerEvents: open ? "auto" : "none",
      transition: `transform 0.36s ${SPRING}, opacity 0.28s ease`,
      padding: "0 16px",
    }}>
      <div style={{
        background: "rgba(10,10,12,0.9)", backdropFilter: "blur(20px)", WebkitBackdropFilter: "blur(20px)",
        border: `1px solid ${BORDER}`, borderRadius: 18, padding: "14px 16px 16px",
        boxShadow: "0 20px 50px #000000cc, inset 0 1px 0 #ffffff08",
      }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 12, gap: 8 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <div style={{ display: "flex", gap: 0, background: SURFACE, borderRadius: PILL_RADIUS + 1, padding: 3, border: `1px solid ${BORDER}` }}>
              {PRO_TABS.map(t => {
                const isActive = active === t;
                const isManual = !autoMap[t];
                return (
                  <button key={t} onClick={() => setActive(t)} style={{
                    position: "relative", background: isActive ? GOLD : "transparent", color: isActive ? BG : MUTED2,
                    border: "none", borderRadius: PILL_RADIUS - 2, padding: PILL_PAD, fontSize: PILL_FONT, letterSpacing: "0.14em",
                    fontFamily: FONT, cursor: "pointer", fontWeight: isActive ? 700 : 400,
                    boxShadow: isActive ? `0 1px 6px ${GOLD}55` : "none", transition: `all 0.2s ${SPRING}`,
                  }}>
                    {t}
                    {/* small dot = this parameter is currently set to MANUAL, visible even when its tab isn't active */}
                    {isManual && (
                      <span style={{
                        position: "absolute", top: 3, right: 3, width: 4, height: 4, borderRadius: "50%",
                        background: isActive ? BG : GOLD_HI, boxShadow: isActive ? "none" : `0 0 4px ${GOLD_HI}99`,
                      }} />
                    )}
                  </button>
                );
              })}
            </div>
            <Divider />
            <button onClick={() => setActive("EV")} style={{
              background: isEv ? GOLD : "transparent", color: isEv ? BG : MUTED2,
              border: `1px solid ${isEv ? GOLD : BORDER}`, borderRadius: 999, padding: PILL_PAD, fontSize: PILL_FONT, letterSpacing: "0.14em",
              fontFamily: FONT, cursor: "pointer", fontWeight: isEv ? 700 : 400,
              boxShadow: isEv ? `0 1px 6px ${GOLD}55` : "none", transition: `all 0.2s ${SPRING}`,
            }}>EV</button>
          </div>
          <button
            onClick={toggleAuto}
            disabled={isEv}
            style={{
              fontFamily: FONT, fontSize: PILL_FONT, fontWeight: 700, letterSpacing: "0.08em",
              padding: PILL_PAD, borderRadius: 999, cursor: isEv ? "default" : "pointer",
              background: isEv ? "transparent" : autoMap[active] ? "transparent" : `${GOLD}22`,
              border: `1px solid ${isEv ? "transparent" : autoMap[active] ? BORDER : GOLD}`,
              color: isEv ? "transparent" : autoMap[active] ? MUTED2 : GOLD_HI,
              opacity: isEv ? 0 : 1, pointerEvents: isEv ? "none" : "auto",
              transition: `all 0.25s ${SPRING}`,
            }}
          >{autoMap[active] ? "AUTO" : "MANUAL"}</button>
        </div>
        <div style={{ display: "flex", justifyContent: "center" }}>
          {/* all wheels stay mounted (hidden, not unmounted) so switching tabs never resets a value you already dialed in */}
          {[...PRO_TABS, "EV"].map(t => (
            <div key={t} style={{
              display: active === t ? "block" : "none",
              opacity: t === "EV" ? 1 : autoMap[t] ? 0.3 : 1,
              pointerEvents: t === "EV" ? "auto" : autoMap[t] ? "none" : "auto",
              transition: "opacity 0.25s ease",
            }}>
              <TickWheel scaleKey={t} defaultIndex={PRO_DEF[t]} active={open && active === t} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// ── Gallery — date-grouped grid, pinch to resize columns, tap for EXIF lightbox ──
function mockShots() {
  const stocks = ["CPM", "D", "FXN", "HLX", "KDK", "NONE"];
  const fxSets = [["Grain", "Light Leak"], ["Vignette", "Halation"], ["Fade", "Dust"], ["Grain"], []];
  const dates = ["TODAY", "YESTERDAY", "JUL 2", "JUN 28"];
  const hues = [28, 200, 340, 45, 165, 260, 10, 190];
  let id = 0;
  return dates.flatMap((date, di) => Array.from({ length: di === 0 ? 5 : 4 }).map(() => {
    id++;
    return {
      id, date,
      hue: hues[id % hues.length],
      iso: [100, 200, 400, 800][id % 4], ss: [60, 125, 250, 500][id % 4], wb: [3200, 5500, 6500][id % 3],
      film: stocks[id % stocks.length], fx: fxSets[id % fxSets.length],
    };
  }));
}
const GALLERY_SHOTS = mockShots();

function Lightbox({ shot, onClose }) {
  const [zoomed, setZoomed] = useState(false);
  if (!shot) return null;
  return (
    <div onClick={onClose} style={{
      position: "fixed", inset: 0, background: "rgba(0,0,0,0.92)", zIndex: 120,
      display: "flex", flexDirection: "column", animation: "fadeInLB 0.22s ease",
    }}>
      <div onClick={(e) => e.stopPropagation()} style={{ flex: 1, display: "flex", alignItems: "center", justifyContent: "center", padding: 20 }}>
        <div
          onDoubleClick={() => setZoomed(z => !z)}
          style={{
            width: "100%", maxWidth: 420, aspectRatio: "3/4", borderRadius: 12,
            background: `linear-gradient(160deg, hsl(${shot.hue},38%,22%), #0a0a0c 75%)`,
            transform: `scale(${zoomed ? 1.5 : 1})`, transition: `transform 0.35s ${SPRING}`,
            boxShadow: "0 30px 80px #000000cc", cursor: "zoom-in",
          }}
        />
      </div>
      <div onClick={(e) => e.stopPropagation()} style={{
        padding: "14px 20px 28px", display: "flex", flexWrap: "wrap", gap: "8px 18px",
        fontFamily: FONT, fontSize: 9.5, color: `${TEXT}cc`, borderTop: `1px solid ${BORDER}`,
      }}>
        <span style={{ color: GOLD }}>ISO {shot.iso}</span>
        <span>1/{shot.ss}</span>
        <span>{shot.wb}K</span>
        <span style={{ color: GOLD_HI }}>{shot.film}</span>
        {shot.fx.map(f => <span key={f} style={{ color: MUTED2 }}>· {f}</span>)}
      </div>
    </div>
  );
}

function GalleryScreen({ open, onBack }) {
  const [cols, setCols] = useState(3);
  const [lightbox, setLightbox] = useState(null);
  const [selectMode, setSelectMode] = useState(false);
  const [selected, setSelected] = useState(() => new Set());
  const pinchDist = useRef(null);
  const pressTimer = useRef(null);
  const pressFired = useRef(false);

  const onTouchStart = (e) => {
    if (e.touches.length === 2) {
      const [a, b] = e.touches;
      pinchDist.current = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
    }
  };
  const onTouchMove = (e) => {
    if (e.touches.length === 2 && pinchDist.current) {
      const [a, b] = e.touches;
      const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      const delta = d - pinchDist.current;
      if (Math.abs(delta) > 40) {
        setCols(c => Math.max(2, Math.min(5, c + (delta > 0 ? -1 : 1))));
        pinchDist.current = d;
        zBuzz(4);
      }
    }
  };
  const onTouchEnd = () => { pinchDist.current = null; };

  const toggleSelect = (id) => {
    setSelected(s => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  };

  const thumbDown = (id) => {
    pressFired.current = false;
    pressTimer.current = setTimeout(() => {
      pressFired.current = true; zBuzz(15);
      setSelectMode(true); toggleSelect(id);
    }, 420);
  };
  const thumbUp = (id, shot) => {
    clearTimeout(pressTimer.current);
    if (pressFired.current) return;
    if (selectMode) toggleSelect(id); else setLightbox(shot);
  };

  const exitSelect = () => { setSelectMode(false); setSelected(new Set()); };

  const groups = GALLERY_SHOTS.reduce((acc, s) => { (acc[s.date] = acc[s.date] || []).push(s); return acc; }, {});
  let flatIdx = 0;

  return (
    <div style={{
      position: "absolute", inset: 0, background: BG, overflowY: "auto", zIndex: 50,
      transform: `translateX(${open ? "0" : "100%"})`,
      transition: `transform 0.4s ${SPRING}`,
      pointerEvents: open ? "auto" : "none",
      boxShadow: open ? "-30px 0 60px #000000aa" : "none",
    }} onTouchStart={onTouchStart} onTouchMove={onTouchMove} onTouchEnd={onTouchEnd}>
      <style>{`
        @keyframes fadeInLB{from{opacity:0}to{opacity:1}}
        @keyframes thumbIn{from{opacity:0; transform:scale(0.9)}to{opacity:1; transform:scale(1)}}
        .galleryThumb{transition:transform 0.15s ${SPRING}}
        .galleryThumb:active{transform:scale(0.93)}
      `}</style>

      <div style={{
        position: "sticky", top: 0, zIndex: 5, display: "flex", alignItems: "center", gap: 12,
        padding: "20px 16px 14px", background: "rgba(3,3,4,0.92)", backdropFilter: "blur(14px)",
        borderBottom: `1px solid ${BORDER}`, minHeight: 24,
      }}>
        {selectMode ? (
          <>
            <button onClick={exitSelect} style={{ background: "transparent", border: "none", cursor: "pointer", color: TEXT, fontSize: 13, fontFamily: FONT, padding: 4 }}>Cancel</button>
            <span style={{ fontFamily: FONT, fontSize: 11, letterSpacing: "0.1em", color: GOLD_HI, marginLeft: "auto" }}>{selected.size} SELECTED</span>
          </>
        ) : (
          <>
            <button onClick={onBack} style={{ background: "transparent", border: "none", cursor: "pointer", color: TEXT, fontSize: 18, padding: 4 }}>‹</button>
            <span style={{ fontFamily: FONT, fontSize: 11, letterSpacing: "0.2em", color: GOLD }}>GALLERY</span>
            <span style={{ marginLeft: "auto", fontFamily: FONT, fontSize: 8, color: MUTED2, letterSpacing: "0.08em" }}>PINCH TO RESIZE</span>
          </>
        )}
      </div>

      {Object.entries(groups).map(([date, shots]) => (
        <div key={date} style={{ padding: "16px 16px 4px" }}>
          <div style={{ fontFamily: FONT, fontSize: 9, letterSpacing: "0.15em", color: MUTED2, marginBottom: 8 }}>{date}</div>
          <div style={{ display: "grid", gridTemplateColumns: `repeat(${cols}, 1fr)`, gap: 4, transition: `all 0.3s ${SPRING}` }}>
            {shots.map(s => {
              const i = flatIdx++;
              const isSel = selected.has(s.id);
              return (
                <button
                  key={s.id} className="galleryThumb"
                  onPointerDown={() => thumbDown(s.id)}
                  onPointerUp={() => thumbUp(s.id, s)}
                  onPointerLeave={() => clearTimeout(pressTimer.current)}
                  style={{
                    aspectRatio: "3/4", border: "none", borderRadius: 6, cursor: "pointer", padding: 0,
                    background: `linear-gradient(160deg, hsl(${s.hue},38%,20%), #0a0a0c 80%)`,
                    position: "relative", overflow: "hidden",
                    animation: `thumbIn 0.32s ${SPRING} both`, animationDelay: `${Math.min(i * 20, 240)}ms`,
                    outline: isSel ? `2px solid ${GOLD}` : "none", outlineOffset: -2,
                  }}
                >
                  <Image size={14} color="rgba(255,255,255,0.18)" strokeWidth={1.4} style={{ position: "absolute", top: "50%", left: "50%", transform: "translate(-50%,-50%)" }} />
                  {s.fx.length > 0 && <span style={{ position: "absolute", bottom: 5, left: 5, width: 4, height: 4, borderRadius: "50%", background: GOLD, boxShadow: `0 0 4px ${GOLD}` }} />}
                  <div style={{
                    position: "absolute", top: 5, right: 5, width: 16, height: 16, borderRadius: "50%",
                    background: isSel ? GOLD : "rgba(0,0,0,0.4)", border: `1px solid ${isSel ? GOLD : "rgba(255,255,255,0.4)"}`,
                    display: selectMode ? "flex" : "none", alignItems: "center", justifyContent: "center",
                    transition: `all 0.18s ${SPRING}`, transform: isSel ? "scale(1)" : "scale(0.85)",
                  }}>
                    {isSel && <span style={{ color: BG, fontSize: 9, fontWeight: 900, lineHeight: 1 }}>✓</span>}
                  </div>
                </button>
              );
            })}
          </div>
        </div>
      ))}

      <div style={{ height: selectMode ? 90 : 24 }} />

      {/* select-mode action bar */}
      <div style={{
        position: "absolute", left: 0, right: 0, bottom: 0, zIndex: 8,
        display: "flex", justifyContent: "space-around", alignItems: "center",
        padding: "14px 20px 22px",
        background: "linear-gradient(to top, rgba(3,3,4,0.96), rgba(3,3,4,0.85))",
        borderTop: `1px solid ${BORDER}`,
        transform: `translateY(${selectMode ? "0" : "100%"})`,
        transition: `transform 0.34s ${SPRING}`,
      }}>
        <button style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4, background: "transparent", border: "none", cursor: "pointer", color: TEXT, opacity: selected.size ? 1 : 0.35 }} disabled={!selected.size}>
          <Share size={17} color={TEXT} strokeWidth={1.7} />
          <span style={{ fontFamily: FONT, fontSize: 7, letterSpacing: "0.08em" }}>SHARE</span>
        </button>
        <button
          onClick={exitSelect}
          style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4, background: "transparent", border: "none", cursor: "pointer", color: RED, opacity: selected.size ? 1 : 0.35 }}
          disabled={!selected.size}
        >
          <Trash2 size={17} color={RED} strokeWidth={1.7} />
          <span style={{ fontFamily: FONT, fontSize: 7, letterSpacing: "0.08em" }}>DELETE</span>
        </button>
      </div>

      <Lightbox shot={lightbox} onClose={() => setLightbox(null)} />
    </div>
  );
}

function GridOverlay({ type }) {
  if (type === "off") return null;
  const lineStyle = { position: "absolute", background: "rgba(255,255,255,0.32)" };
  if (type === "diagonal") {
    return (
      <svg style={{ position: "absolute", inset: 0, width: "100%", height: "100%" }} preserveAspectRatio="none">
        <line x1="0" y1="0" x2="100%" y2="100%" stroke="rgba(255,255,255,0.32)" strokeWidth="1" />
        <line x1="100%" y1="0" x2="0" y2="100%" stroke="rgba(255,255,255,0.32)" strokeWidth="1" />
      </svg>
    );
  }
  const positions = { "3x3": [33.33, 66.67], "4x4": [25, 50, 75], golden: [38.2, 61.8] }[type] || [];
  return (
    <>
      {positions.map(p => <div key={`v${p}`} style={{ ...lineStyle, left: `${p}%`, top: 0, bottom: 0, width: 1 }} />)}
      {positions.map(p => <div key={`h${p}`} style={{ ...lineStyle, top: `${p}%`, left: 0, right: 0, height: 1 }} />)}
    </>
  );
}

export default function CameraScreen() {
  const [mode, setMode] = useState("photo");
  const [isRecording, setIsRecording] = useState(false);
  const [recordTime, setRecordTime] = useState(0);
  const [sheetOpen, setSheetOpen] = useState(false);
  const [proOpen, setProOpen] = useState(false);
  const [view, setView] = useState("camera");
  const [aspect, setAspect] = useState("FULL");
  const [grid, setGrid] = useState("off");
  const [timer, setTimer] = useState("off");
  const [flash, setFlash] = useState("off");
  const [flipped, setFlipped] = useState(false);
  const [focusPt, setFocusPt] = useState(null);
  const recInterval = useRef(null);
  const focusTimer = useRef(null);

  // bug fix: interval/timeout were only ever cleared by their own stop handlers,
  // so navigating away mid-recording or mid-focus-ring left them running.
  useEffect(() => () => { clearInterval(recInterval.current); clearTimeout(focusTimer.current); }, []);

  const tapToFocus = (e) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = (e.touches ? e.touches[0].clientX : e.clientX) - rect.left;
    const y = (e.touches ? e.touches[0].clientY : e.clientY) - rect.top;
    setFocusPt({ x, y, id: Date.now() });
    if (navigator.vibrate) navigator.vibrate(6);
    clearTimeout(focusTimer.current);
    focusTimer.current = setTimeout(() => setFocusPt(null), 750);
  };

  const armVideo = useCallback(() => {
    setIsRecording(true); setMode("video");
    if (navigator.vibrate) navigator.vibrate([15, 30, 15]);
    recInterval.current = setInterval(() => setRecordTime(t => t + 1), 1000);
  }, []);
  const stopVideo = useCallback(() => {
    setIsRecording(false); clearInterval(recInterval.current); setRecordTime(0);
    if (navigator.vibrate) navigator.vibrate(20);
  }, []);
  const capture = useCallback(() => { if (navigator.vibrate) navigator.vibrate(12); }, []);

  const fmt = (s) => `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;

  return (
    <div style={{ position: "relative", width: "100%", height: "100dvh", background: BG, overflow: "hidden", fontFamily: FONT, userSelect: "none" }}>
      <style>{`
        @keyframes pillDot { 0%,100%{opacity:1} 50%{opacity:0.25} }
        @keyframes modePop { 0%{opacity:0; transform:scale(0.7)} 100%{opacity:1; transform:scale(1)} }
        @keyframes shutterRipple { 0%{opacity:0.9; transform:scale(0.6)} 100%{opacity:0; transform:scale(1.7)} }
        @keyframes spin { from{transform:rotate(0deg)} to{transform:rotate(360deg)} }
        @keyframes grainMove { 0%{transform:translate(0,0)} 100%{transform:translate(-3%,-4%)} }
        @keyframes optPop { 0%{opacity:0; transform:translateY(4px) scale(0.85)} 100%{opacity:1; transform:translateY(0) scale(1)} }
        @keyframes gridLineIn { 0%{opacity:0; transform:scale(0.5)} 100%{opacity:0.85; transform:scale(1)} }
        @keyframes focusPop { 0%{opacity:0; transform:translate(-50%,-50%) scale(1.35)} 15%{opacity:1; transform:translate(-50%,-50%) scale(1)} 80%{opacity:1} 100%{opacity:0; transform:translate(-50%,-50%) scale(0.92)} }
      `}</style>

      {/* camera layer — recedes slightly when Gallery slides over it */}
      <div style={{
        position: "absolute", inset: 0,
        transform: view === "gallery" ? "scale(0.94) translateY(10px)" : "scale(1) translateY(0)",
        filter: view === "gallery" ? "brightness(0.5)" : "brightness(1)",
        transition: `transform 0.42s ${SPRING}, filter 0.42s ease`,
        pointerEvents: view === "gallery" ? "none" : "auto",
      }}>
        {/* viewfinder — letterboxes to the chosen aspect ratio, shows the active grid, tap anywhere to focus */}
        <div
          onClick={tapToFocus}
          style={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center", cursor: "crosshair" }}
        >
          <div style={{
            position: "relative", width: "100%",
            height: aspect === "FULL" ? "100%" : "auto",
            aspectRatio: aspect === "FULL" ? undefined : ASPECT_CSS[aspect],
            maxHeight: "100%", overflow: "hidden",
            background: "radial-gradient(ellipse at 50% 35%, #16161a 0%, #030304 78%)",
            transition: `aspect-ratio 0.4s ${SPRING}, height 0.4s ${SPRING}`,
            transform: flipped ? "scaleX(-1)" : "none",
          }}>
            <div style={{
              position: "absolute", inset: 0, opacity: 0.04, mixBlendMode: "overlay",
              backgroundImage: "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='90' height='90'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E\")",
              animation: "grainMove 0.4s steps(2) infinite",
            }} />
            <GridOverlay type={grid} />
            {focusPt && (
              <div key={focusPt.id} style={{
                position: "absolute", left: focusPt.x, top: focusPt.y, width: 56, height: 56,
                transform: "translate(-50%,-50%)", border: `1.4px solid ${GOLD_HI}`, borderRadius: 6,
                animation: "focusPop 0.7s cubic-bezier(0.16,1,0.3,1) forwards", pointerEvents: "none",
              }}>
                <span style={{ position: "absolute", top: -4, left: -4, width: 8, height: 8, borderTop: `1.4px solid ${GOLD_HI}`, borderLeft: `1.4px solid ${GOLD_HI}` }} />
                <span style={{ position: "absolute", top: -4, right: -4, width: 8, height: 8, borderTop: `1.4px solid ${GOLD_HI}`, borderRight: `1.4px solid ${GOLD_HI}` }} />
                <span style={{ position: "absolute", bottom: -4, left: -4, width: 8, height: 8, borderBottom: `1.4px solid ${GOLD_HI}`, borderLeft: `1.4px solid ${GOLD_HI}` }} />
                <span style={{ position: "absolute", bottom: -4, right: -4, width: 8, height: 8, borderBottom: `1.4px solid ${GOLD_HI}`, borderRight: `1.4px solid ${GOLD_HI}` }} />
              </div>
            )}
          </div>
        </div>

        {/* recording timer readout, replaces flash flicker with clean HUD text */}
        {isRecording && (
          <div style={{
            position: "absolute", top: 68, left: "50%", transform: "translateX(-50%)", zIndex: 30,
            display: "flex", alignItems: "center", gap: 6,
            color: RED, fontSize: 15, fontWeight: 700, letterSpacing: 1, fontVariantNumeric: "tabular-nums",
            textShadow: "0 0 10px rgba(226,56,56,0.5)",
          }}>
            <span style={{ width: 6, height: 6, borderRadius: "50%", background: RED, animation: "pillDot 1s infinite" }} />
            {fmt(recordTime)}
          </div>
        )}

        <TopControls aspect={aspect} setAspect={setAspect} grid={grid} setGrid={setGrid} timer={timer} setTimer={setTimer} flash={flash} setFlash={setFlash} flipped={flipped} setFlipped={setFlipped} />

        <ModePill mode={mode} setMode={setMode} disabled={isRecording} />
        <ZoomPill disabled={sheetOpen} />
        <ProPanel open={proOpen} />

        {/* bottom control bar */}
        <div style={{
          position: "absolute", bottom: 0, left: 0, right: 0, height: 112,
          background: "linear-gradient(to top, rgba(3,3,4,0.9), rgba(3,3,4,0))",
          display: "flex", alignItems: "center", justifyContent: "space-between",
          padding: "0 26px 18px", zIndex: 30,
        }}>
          <BottomIconButton icon={Image} label="GALLERY" disabled={isRecording} onClick={() => setView("gallery")} />
          <BottomIconButton icon={SlidersHorizontal} label="PRO" active={proOpen} disabled={isRecording} onClick={() => setProOpen(o => !o)} />
          <Shutter mode={mode} isRecording={isRecording} onArmVideo={armVideo} onStopVideo={stopVideo} onCapture={capture} />
          <BottomIconButton icon={Menu} label="FILM" active={sheetOpen} disabled={isRecording} onClick={() => setSheetOpen(o => !o)} />
          <div style={{ width: 44 }} />
        </div>

        {sheetOpen && <AnalogSheet onClose={() => setSheetOpen(false)} onApply={() => setSheetOpen(false)} />}
      </div>

      <GalleryScreen open={view === "gallery"} onBack={() => setView("camera")} />
    </div>
  );
}
