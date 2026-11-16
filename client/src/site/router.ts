import { useEffect, useState, type MouseEvent } from "react";

export function normalizePath(path: string): string {
  if (path.length > 1 && path.endsWith("/")) return path.slice(0, -1);
  return path || "/";
}

export function navigate(to: string) {
  const next = normalizePath(to);
  if (normalizePath(location.pathname) === next) return;
  history.pushState({}, "", next);
  window.dispatchEvent(new Event("bleep-nav"));
}

export function useRoute(): string {
  const [path, setPath] = useState(() => normalizePath(location.pathname));
  useEffect(() => {
    const sync = () => setPath(normalizePath(location.pathname));
    window.addEventListener("popstate", sync);
    window.addEventListener("bleep-nav", sync);
    return () => {
      window.removeEventListener("popstate", sync);
      window.removeEventListener("bleep-nav", sync);
    };
  }, []);
  return path;
}

export function onSiteClick(event: MouseEvent<HTMLAnchorElement>) {
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0) return;
  const href = event.currentTarget.getAttribute("href");
  if (!href || !href.startsWith("/") || href.startsWith("//")) return;
  if (href.startsWith("/hashes") || href.startsWith("/download") || href.startsWith("/v1")) return;
  event.preventDefault();
  navigate(href);
}
