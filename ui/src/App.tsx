import { useCallback, useEffect, useRef, useState } from "react";
import { BrowserRouter } from "react-router-dom";
import IntroSplash from "./components/IntroSplash.tsx";
import RGMALayout from "./components/RGMALayout.tsx";
import { ChatSessionsProvider } from "./contexts/ChatSessionsContext.tsx";

function App() {
  const [phase, setPhase] = useState<"idle" | "opening" | "done">("idle");
  const doneTimerRef = useRef<number | undefined>(undefined);

  const startOpen = useCallback(() => {
    setPhase((current) => (current === "idle" ? "opening" : current));
  }, []);

  useEffect(() => {
    return () => {
      window.clearTimeout(doneTimerRef.current);
    };
  }, []);

  useEffect(() => {
    if (phase !== "opening") {
      return;
    }

    doneTimerRef.current = window.setTimeout(() => {
      setPhase("done");
    }, 1200);
  }, [phase]);

  return (
    <>
      <div className={`app-shell ${phase === "done" ? "is-ready" : ""}`}>
        <BrowserRouter>
          <ChatSessionsProvider>
            <RGMALayout />
          </ChatSessionsProvider>
        </BrowserRouter>
      </div>
      {phase !== "done" && (
        <IntroSplash
          phase={phase}
          onStart={startOpen}
          word="RGMuseum Assistant"
        />
      )}
    </>
  );
}

export default App;
