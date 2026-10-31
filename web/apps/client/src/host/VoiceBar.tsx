import { useEffect, useRef, useState } from "react";
import { PauseIcon, PlayIcon, VoiceIcon } from "../components/icons.js";
import { useI18n } from "../i18n/I18nProvider.js";
import { formatVoiceDuration } from "./format.js";

interface VoiceBarProps {
  /** 可播放地址；undefined 时只能降级成静态时长条。 */
  readonly src?: string | undefined;
  /** Host 下发的时长（毫秒）；没有就从音频元数据里读。 */
  readonly durationMs?: number | undefined;
}

/**
 * 语音条。
 *
 * - 有可访问地址：播放/暂停 + 进度 + 时长，底层就是一个 `<audio preload="metadata">`；
 * - 只有时长（老 Host 给的是 `file://` 引用）：降级成「语音 · 12″」静态条；
 * - 音频加载 / 播放失败也退回静态条，并标出「无法播放」，不留一个哑掉的控制条。
 */
export function VoiceBar({ src, durationMs }: VoiceBarProps) {
  const { t } = useI18n();
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [measuredMs, setMeasuredMs] = useState<number | undefined>(undefined);
  const [failed, setFailed] = useState(false);

  // 同一条消息节点被复用到另一条语音上时，把播放态归零。
  useEffect(() => {
    setPlaying(false);
    setProgress(0);
    setMeasuredMs(undefined);
    setFailed(false);
  }, [src]);

  const totalMs = durationMs !== undefined && durationMs > 0 ? durationMs : measuredMs;
  const duration = formatVoiceDuration(totalMs);
  const label = duration.length > 0 ? duration : t("voice.unknown");
  const aria = duration.length > 0 ? t("voice.aria", { duration }) : t("voice.unknown");

  if (src === undefined || failed) {
    return (
      <div className="host-voice is-static" role="group" aria-label={aria}>
        <span className="host-voice-icon">
          <VoiceIcon />
        </span>
        <span className="host-voice-time">{duration.length > 0 ? t("voice.static", { duration }) : t("voice.unknown")}</span>
        {failed && <span className="host-voice-note">{t("voice.cannotPlay")}</span>}
      </div>
    );
  }

  const toggle = () => {
    const audio = audioRef.current;
    if (audio === null) return;
    if (playing) {
      audio.pause();
      return;
    }
    void audio.play().catch(() => setFailed(true));
  };

  return (
    <div className="host-voice" role="group" aria-label={aria}>
      <button
        className="quiet host-voice-button"
        type="button"
        aria-label={playing ? t("voice.pause") : t("voice.play")}
        onClick={toggle}
      >
        {playing ? <PauseIcon /> : <PlayIcon />}
      </button>
      <span className="host-voice-track" aria-hidden="true">
        <span className="host-voice-fill" style={{ width: `${Math.round(Math.min(1, progress) * 100)}%` }} />
      </span>
      <span className="host-voice-time">
        {playing ? formatVoiceDuration(Math.max(1, (totalMs ?? 0) * progress)) || label : label}
      </span>
      <audio
        ref={audioRef}
        className="host-voice-audio"
        src={src}
        preload="metadata"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => {
          setPlaying(false);
          setProgress(0);
        }}
        onTimeUpdate={(event) => {
          const audio = event.currentTarget;
          if (Number.isFinite(audio.duration) && audio.duration > 0) setProgress(audio.currentTime / audio.duration);
        }}
        onLoadedMetadata={(event) => {
          const seconds = event.currentTarget.duration;
          if (Number.isFinite(seconds) && seconds > 0) setMeasuredMs(Math.round(seconds * 1_000));
        }}
        onError={() => setFailed(true)}
      />
    </div>
  );
}
