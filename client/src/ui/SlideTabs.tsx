import { useLayoutEffect, useRef, type KeyboardEvent, type ReactNode } from "react";

export function SlideTabs({
  labels,
  index,
  onIndex,
  children,
  label,
}: {
  labels: readonly string[];
  index: number;
  onIndex: (i: number) => void;
  children: ReactNode[];
  label: string;
}) {
  const trackRef = useRef<HTMLDivElement>(null);
  const btnRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const pillRef = useRef<HTMLSpanElement>(null);
  const drag = useRef<{ x: number; i: number; moved: boolean } | null>(null);
  const suppressClick = useRef(false);
  const slug = label.toLowerCase().replace(/\s+/g, "-");

  useLayoutEffect(() => {
    const track = trackRef.current;
    const btn = btnRefs.current[index];
    const pill = pillRef.current;
    if (!track || !btn || !pill) return;
    const place = () => {
      pill.style.width = `${btn.offsetWidth}px`;
      pill.style.transform = `translateX(${btn.offsetLeft}px)`;
      const left = btn.offsetLeft - (track.clientWidth - btn.offsetWidth) / 2;
      const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      track.scrollTo({ left: Math.max(0, left), behavior: reduce ? "auto" : "smooth" });
    };
    place();
    const ro = new ResizeObserver(place);
    ro.observe(track);
    return () => ro.disconnect();
  }, [index, labels]);

  function onPointerDown(e: React.PointerEvent<HTMLDivElement>) {
    if (e.button !== 0) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { x: e.clientX, i: index, moved: false };
  }

  function onPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const d = drag.current;
    if (!d) return;
    const dx = e.clientX - d.x;
    if (Math.abs(dx) < 48) return;
    d.moved = true;
    const next = dx < 0 ? Math.min(labels.length - 1, d.i + 1) : Math.max(0, d.i - 1);
    if (next === d.i) return;
    d.i = next;
    d.x = e.clientX;
    onIndex(next);
  }

  function onPointerUp() {
    if (drag.current?.moved) suppressClick.current = true;
    drag.current = null;
  }

  function onKey(e: KeyboardEvent<HTMLDivElement>) {
    if (e.key === "ArrowRight") {
      e.preventDefault();
      onIndex(Math.min(labels.length - 1, index + 1));
    }
    if (e.key === "ArrowLeft") {
      e.preventDefault();
      onIndex(Math.max(0, index - 1));
    }
  }

  return (
    <div className="slider">
      <div
        className="slider-track"
        role="tablist"
        aria-label={label}
        ref={trackRef}
        onKeyDown={onKey}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        <span className="slider-pill" ref={pillRef} />
        {labels.map((name, i) => (
          <button
            key={name}
            type="button"
            role="tab"
            id={`${slug}-tab-${i}`}
            aria-selected={i === index}
            aria-controls={`${slug}-panel-${i}`}
            tabIndex={i === index ? 0 : -1}
            ref={(el) => { btnRefs.current[i] = el; }}
            className={i === index ? "on" : ""}
            onClick={() => {
              if (suppressClick.current) {
                suppressClick.current = false;
                return;
              }
              onIndex(i);
            }}
          >
            {name}
          </button>
        ))}
      </div>
      <div className="slider-view">
        <div className="slider-panels" style={{ transform: `translateX(-${index * 100}%)` }}>
          {children.map((child, i) => (
            <div
              key={labels[i]}
              id={`${slug}-panel-${i}`}
              role="tabpanel"
              aria-labelledby={`${slug}-tab-${i}`}
              aria-hidden={i !== index}
              inert={i !== index}
            >
              {child}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
