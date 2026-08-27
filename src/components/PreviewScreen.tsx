import React, { useRef, useEffect } from 'react';
import { ZestoUiState, FrameHealthState } from '../types.ts';
import { Play, Square, Video, ShieldCheck, Activity, Cpu, Sparkles } from 'lucide-react';

interface PreviewScreenProps {
  uiState: ZestoUiState;
  onStartDecoding: () => void;
  onStopDecoding: () => void;
}

export const PreviewScreen: React.FC<PreviewScreenProps> = ({
  uiState,
  onStartDecoding,
  onStopDecoding
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const animationFrameId = useRef<number | null>(null);

  const isLive = uiState.isConnected || uiState.isDecoding;
  const healthState = uiState.diagnosticsSnapshot.frameHealthState;
  const fps = uiState.diagnosticsSnapshot.decoderFps || (isLive ? uiState.streamConfig.targetFps : 0);
  const resolution = `${uiState.streamConfig.targetWidth} x ${uiState.streamConfig.targetHeight}`;
  const latency = `${uiState.diagnosticsSnapshot.decoderStats.averageDecodeLatencyMs.toFixed(1)} ms`;
  const apiLabel = `${uiState.cameraCapabilities.apiType} (${uiState.cameraCapabilities.hardwareLevel.split(' ')[0]})`;

  // Start real webcam stream if source is webcam and decoding is active
  useEffect(() => {
    let localStream: MediaStream | null = null;

    if (isLive && uiState.inputSourceType === 'webcam') {
      navigator.mediaDevices
        ?.getUserMedia({
          video: {
            width: { ideal: uiState.streamConfig.targetWidth },
            height: { ideal: uiState.streamConfig.targetHeight }
          }
        })
        .then((stream) => {
          localStream = stream;
          if (videoRef.current) {
            videoRef.current.srcObject = stream;
            videoRef.current.play().catch(() => {});
          }
        })
        .catch((err) => {
          console.warn('Webcam permission or access error:', err);
        });
    } else {
      if (videoRef.current && videoRef.current.srcObject) {
        const stream = videoRef.current.srcObject as MediaStream;
        stream.getTracks().forEach((t) => t.stop());
        videoRef.current.srcObject = null;
      }
    }

    return () => {
      if (localStream) {
        localStream.getTracks().forEach((t) => t.stop());
      }
    };
  }, [isLive, uiState.inputSourceType, uiState.streamConfig.targetWidth, uiState.streamConfig.targetHeight]);

  // Live Canvas Rendering Loop (mimicking ZestoFrameTransformer)
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let frameNumber = 0;

    const render = () => {
      frameNumber++;
      const width = canvas.width;
      const height = canvas.height;

      if (!isLive) {
        // Standby Screen
        ctx.fillStyle = '#111214';
        ctx.fillRect(0, 0, width, height);

        // Grid lines
        ctx.strokeStyle = '#222427';
        ctx.lineWidth = 1;
        const step = 40;
        for (let x = 0; x < width; x += step) {
          ctx.beginPath();
          ctx.moveTo(x, 0);
          ctx.lineTo(x, height);
          ctx.stroke();
        }
        for (let y = 0; y < height; y += step) {
          ctx.beginPath();
          ctx.moveTo(0, y);
          ctx.lineTo(width, y);
          ctx.stroke();
        }

        // Center crosshair and prompt
        ctx.strokeStyle = '#44474E';
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.arc(width / 2, height / 2, 48, 0, Math.PI * 2);
        ctx.stroke();

        ctx.fillStyle = '#8E919A';
        ctx.font = '14px "JetBrains Mono", monospace';
        ctx.textAlign = 'center';
        ctx.fillText('NO ACTIVE STREAM CARRIER', width / 2, height / 2 + 80);

        ctx.fillStyle = '#64748B';
        ctx.font = '11px "Plus Jakarta Sans", sans-serif';
        ctx.fillText('Press START PREVIEW or CONNECT in Config tab', width / 2, height / 2 + 104);
      } else {
        if (uiState.inputSourceType === 'webcam' && videoRef.current && videoRef.current.readyState >= 2) {
          // Render Real Webcam frame
          ctx.drawImage(videoRef.current, 0, 0, width, height);
        } else {
          // Render Simulated RTSP / Color Test Pattern Stream
          // Background Gradient animation
          const t = Date.now() * 0.001;
          const grad = ctx.createLinearGradient(
            Math.sin(t * 0.5) * width,
            0,
            Math.cos(t * 0.5) * width,
            height
          );
          grad.addColorStop(0, '#1E1B4B');
          grad.addColorStop(0.5, '#0F172A');
          grad.addColorStop(1, '#064E3B');
          ctx.fillStyle = grad;
          ctx.fillRect(0, 0, width, height);

          // SMPTE-style bottom color bars
          const barHeight = height * 0.22;
          const barY = height - barHeight;
          const colors = ['#FFFFFF', '#FFFF00', '#00FFFF', '#00FF00', '#FF00FF', '#FF0000', '#0000FF'];
          const barWidth = width / colors.length;
          colors.forEach((col, idx) => {
            ctx.fillStyle = col;
            ctx.fillRect(idx * barWidth, barY, barWidth, barHeight);
          });

          // Moving target circle for frame rate verification
          const speed = 2.5;
          const circleX = (Math.sin(t * speed) * 0.4 + 0.5) * width;
          const circleY = (Math.cos(t * speed * 0.8) * 0.25 + 0.4) * height;

          ctx.fillStyle = '#D0BCFF';
          ctx.beginPath();
          ctx.arc(circleX, circleY, 24, 0, Math.PI * 2);
          ctx.fill();
          ctx.lineWidth = 3;
          ctx.strokeStyle = '#381E72';
          ctx.stroke();

          // Center Reticle
          ctx.strokeStyle = 'rgba(255, 255, 255, 0.4)';
          ctx.lineWidth = 1;
          ctx.beginPath();
          ctx.moveTo(width / 2 - 20, height / 2);
          ctx.lineTo(width / 2 + 20, height / 2);
          ctx.moveTo(width / 2, height / 2 - 20);
          ctx.lineTo(width / 2, height / 2 + 20);
          ctx.stroke();
        }

        // Live Overlays: Watermark & Telemetry Banner
        ctx.fillStyle = 'rgba(0, 0, 0, 0.65)';
        ctx.fillRect(16, 16, 280, 72);
        ctx.strokeStyle = '#44474E';
        ctx.strokeRect(16, 16, 280, 72);

        ctx.fillStyle = '#34D399';
        ctx.font = 'bold 12px "JetBrains Mono", monospace';
        ctx.textAlign = 'left';
        ctx.fillText(`ZESTO VIRTUAL FEED: ACTIVE`, 28, 38);

        ctx.fillStyle = '#E2E2E6';
        ctx.font = '11px "JetBrains Mono", monospace';
        ctx.fillText(`FRAME #${frameNumber} | ${fps.toFixed(1)} FPS`, 28, 56);
        ctx.fillText(`TIME: ${new Date().toISOString().slice(11, 23)}`, 28, 74);
      }

      animationFrameId.current = requestAnimationFrame(render);
    };

    render();

    return () => {
      if (animationFrameId.current) {
        cancelAnimationFrame(animationFrameId.current);
      }
    };
  }, [isLive, fps, uiState.inputSourceType]);

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-24 sm:pb-8">
      {/* Hidden video element for live webcam capture */}
      <video ref={videoRef} className="hidden" playsInline muted autoPlay />

      {/* Main Viewport Card */}
      <div className="bg-[#1A1C1E] border border-[#44474E] rounded-[24px] overflow-hidden shadow-2xl relative">
        <div className="relative aspect-video w-full bg-black flex items-center justify-center">
          <canvas
            ref={canvasRef}
            width={uiState.streamConfig.targetWidth}
            height={uiState.streamConfig.targetHeight}
            className="w-full h-full object-contain"
          />

          {/* Top HUD Overlay */}
          <div className="absolute top-3 left-3 right-3 flex items-center justify-between pointer-events-none">
            {/* Left Status Badge */}
            <div className="bg-black/75 backdrop-blur-sm border border-[#44474E] px-3 py-1.5 rounded-xl flex items-center gap-2">
              <div
                className={`w-2 h-2 rounded-full ${
                  isLive ? 'bg-[#34D399] animate-pulse' : 'bg-[#EF4444]'
                }`}
              />
              <span className="text-[11px] font-mono font-bold text-white tracking-wide">
                {isLive ? 'LIVE RTSP FEED' : 'PREVIEW STANDBY'}
              </span>
            </div>

            {/* Right Health & Metrics HUD */}
            <div className="flex items-center gap-2">
              <div
                className={`px-2.5 py-1 rounded-xl text-[10px] font-mono font-bold uppercase ${
                  healthState === FrameHealthState.FRAME_ACTIVE
                    ? 'bg-[#064E3B] text-[#34D399] border border-[#34D399]/40'
                    : healthState === FrameHealthState.FRAME_STALLED
                    ? 'bg-[#78350F] text-[#FBBF24] border border-[#FBBF24]/40'
                    : 'bg-[#7F1D1D] text-[#F87171] border border-[#EF4444]/40'
                }`}
              >
                {healthState}
              </div>

              <div className="hidden sm:flex bg-black/75 backdrop-blur-sm border border-[#44474E] px-2.5 py-1 rounded-xl text-[10px] font-mono text-[#C4C6CF] gap-2">
                <span>DEC: {latency}</span>
                <span>•</span>
                <span>DROP: {uiState.diagnosticsSnapshot.decoderDroppedFrames}</span>
              </div>
            </div>
          </div>

          {/* Bottom HUD Hardware Acceleration Overlay */}
          <div className="absolute bottom-3 left-3 pointer-events-none">
            <div className="bg-black/80 backdrop-blur-sm border border-[#44474E]/60 px-3 py-1.5 rounded-xl flex items-center gap-2 text-[11px] font-mono text-[#D0BCFF]">
              <Cpu className="w-3.5 h-3.5" />
              <span>H.264 / MediaCodec HW Decoder</span>
            </div>
          </div>
        </div>

        {/* Viewport Control Bar */}
        <div className="bg-[#2D3033] px-6 py-4 border-t border-[#44474E] flex flex-col sm:flex-row items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <span className="text-xs font-mono text-[#C4C6CF]">STREAM PIPELINE:</span>
            <span className="text-xs font-mono font-bold text-[#E2E2E6] bg-[#1A1C1E] px-2.5 py-1 rounded-lg border border-[#44474E]">
              {uiState.streamConfig.url}
            </span>
          </div>

          <div className="flex items-center gap-3 w-full sm:w-auto">
            {!isLive ? (
              <button
                onClick={onStartDecoding}
                className="w-full sm:w-auto px-6 py-2.5 rounded-xl bg-[#D0BCFF] hover:bg-[#EADDFF] text-[#381E72] font-bold text-xs tracking-wider font-mono flex items-center justify-center gap-2 transition-all shadow-md active:scale-95"
              >
                <Play className="w-4 h-4 fill-current" />
                <span>START PREVIEW</span>
              </button>
            ) : (
              <button
                onClick={onStopDecoding}
                className="w-full sm:w-auto px-6 py-2.5 rounded-xl bg-[#7F1D1D] hover:bg-[#991B1B] text-white font-bold text-xs tracking-wider font-mono flex items-center justify-center gap-2 transition-all shadow-md active:scale-95"
              >
                <Square className="w-4 h-4 fill-current" />
                <span>STOP PREVIEW</span>
              </button>
            )}
          </div>
        </div>
      </div>

      {/* 2x2 Telemetry Grid */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Card 1: Resolution */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-4.5 space-y-1.5 shadow">
          <div className="flex items-center justify-between text-[#8E919A]">
            <span className="text-[11px] font-mono font-semibold">RESOLUTION</span>
            <Video className="w-4 h-4 text-[#D0BCFF]" />
          </div>
          <div className="text-lg font-mono font-bold text-[#E2E2E6] tracking-tight">
            {resolution}
          </div>
          <div className="text-[11px] text-[#8E919A]">Surface output format</div>
        </div>

        {/* Card 2: Framerate */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-4.5 space-y-1.5 shadow">
          <div className="flex items-center justify-between text-[#8E919A]">
            <span className="text-[11px] font-mono font-semibold">FRAMERATE</span>
            <Activity className="w-4 h-4 text-[#34D399]" />
          </div>
          <div className="text-lg font-mono font-bold text-[#34D399] tracking-tight">
            {fps.toFixed(1)} FPS
          </div>
          <div className="text-[11px] text-[#8E919A]">Realtime render loop</div>
        </div>

        {/* Card 3: Detected API */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-4.5 space-y-1.5 shadow">
          <div className="flex items-center justify-between text-[#8E919A]">
            <span className="text-[11px] font-mono font-semibold">DETECTED API</span>
            <ShieldCheck className="w-4 h-4 text-[#D0BCFF]" />
          </div>
          <div className="text-sm font-mono font-bold text-[#E2E2E6] truncate" title={apiLabel}>
            {apiLabel}
          </div>
          <div className="text-[11px] text-[#8E919A]">HAL hardware Level 3</div>
        </div>

        {/* Card 4: Decode Latency */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-4.5 space-y-1.5 shadow">
          <div className="flex items-center justify-between text-[#8E919A]">
            <span className="text-[11px] font-mono font-semibold">LATENCY</span>
            <Sparkles className="w-4 h-4 text-[#FBBF24]" />
          </div>
          <div className="text-lg font-mono font-bold text-[#E2E2E6] tracking-tight">
            {latency}
          </div>
          <div className="text-[11px] text-[#8E919A]">Hardware decode pass</div>
        </div>
      </div>
    </div>
  );
};
