import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { Site } from "./site/Site";
import { useRoute } from "./site/router";
import { App } from "./ui/App";
import "./styles.css";

function Root() {
  const path = useRoute();
  if (path === "/app" || path === "/register" || path === "/signin") return <App path={path} />;
  return <Site path={path} />;
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
