type IntroSplashProps = {
  phase: "idle" | "opening";
  onStart: () => void;
  word?: string;
};

export default function IntroSplash({
  phase,
  onStart,
  word = "RGMuseum Assistant",
}: IntroSplashProps) {
  return (
    <button
      type="button"
      className={`intro-splash ${phase === "opening" ? "is-opening" : ""}`}
      onClick={onStart}
      disabled={phase === "opening"}
      aria-label="Open site"
    >
      <span className="intro-orb intro-orb-a" />
      <span className="intro-orb intro-orb-b" />
      <span className="intro-word">{word}</span>
    </button>
  );
}
