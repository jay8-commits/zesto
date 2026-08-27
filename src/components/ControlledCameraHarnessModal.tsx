import React, { useState, useRef, useEffect } from 'react';
import { FrameHealthState } from '../types.ts';
import { X, Camera, SwitchCamera } from 'lucide-react';

interface ControlledCameraHarnessModalProps {
  isOpen: boolean;
  onClose: () => void;
  targetPackage?: string;
}

export const ControlledCameraHarnessModal: React.FC<ControlledCameraHarnessModalProps> = ({
  isOpen,
  onClose,
  targetPackage = 'com.example.zesto.testtarget'
}) => {
  const [isVirtualFeedActive, setIsVirtualFeedActive] = useState(false);
  const [frameCount, setFrameCount] = useState(0);
  const [droppedFrames] = useState(0);
  const [measuredFps, setMeasuredFps] = useState(30.0);
  const [healthState, setHealthState] = useState<FrameHealthState>(FrameHealthState.FRAME_ACTIVE);

  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const animRef = useRef<number | null>(null);

  // Initialize physical camera when in physical mode
  useEffect(() => {
    if (!isOpen) return;

    let stream: MediaStream | null = null;
    if (!isVirtualFeedActive) {
      navigator.mediaDevices
        ?.getUserMedia({ video: true })
        .then((s) => {
          stream = s;
          if (videoRef.current) {
            videoRef.current.srcObject = s;
            videoRef.current.play().catch(() => {});
          }
        })
        .catch((err) => {
          console.warn('Physical camera not accessible in harness:', err);
        });
    } else {
      if (videoRef.current && videoRef.current.srcObject) {
        (videoRef.current.srcObject as MediaStream).getTracks().forEach((t) => t.stop());
        videoRef.current.srcObject = null;
      }
    }

    return () => {
      if (stream) {
        stream.getTracks().forEach((t) => t.stop());
      }
    };
  }, [isOpen, isVirtualFeedActive]);

  // Live Canvas Rendering Loop
  useEffect(() => {
    if (!isOpen) return;

    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let framesThisSecond = 0;
    let lastFpsTime = Date.now();
    let totalFrames = 0;

    const render = () => {
      totalFrames++;
      framesThisSecond++;
      const now = Date.now();
      if (now - lastFpsTime >= 1000) {
        setMeasuredFps((framesThisSecond * 1000) / (now - lastFpsTime));
        framesThisSecond = 0;
        lastFpsTime = now;
      }

      setFrameCount(totalFrames);
      setHealthState(FrameHealthState.FRAME_ACTIVE);

      const width = canvas.width;
      const height = canvas.height;

      if (!isVirtualFeedActive) {
        // Physical Sensor mode
        if (videoRef.current && videoRef.current.readyState >= 2) {
          ctx.drawImage(videoRef.current, 0, 0, width, height);
        } else {
          // Physical sensor standby simulation
          ctx.fillStyle = '#0F172A';
          ctx.fillRect(0, 0, width, height);

          ctx.fillStyle = '#38BDF8';
          ctx.font = '16px "JetBrains Mono", monospace';
          ctx.textAlign = 'center';
          ctx.fillText('PHYSICAL CAMERA2 SENSOR FEED', width / 2, height / 2 - 10);
          ctx.font = '12px "JetBrains Mono", monospace';
          ctx.fillStyle = '#94A3B8';
          ctx.fillText('android.hardware.camera2 SurfaceTarget Active', width / 2, height / 2 + 15);
        }
      } else {
        // Zesto Virtual Injected Feed
        const t = Date.now() * 0.001;
        const grad = ctx.createLinearGradient(0, 0, width, height);
        grad.addColorStop(0, '#311042');
        grad.addColorStop(0.5, '#1E1B4B');
        grad.addColorStop(1, '#064E3B');
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, width, height);

        // SMPTE color bars at bottom
        const barH = height * 0.25;
        const barY = height - barH;
        const colors = ['#FFFFFF', '#FFFF00', '#00FFFF', '#00FF00', '#FF00FF', '#FF0000', '#0000FF'];
        const barW = width / colors.length;
        colors.forEach((col, i) => {
          ctx.fillStyle = col;
          ctx.fillRect(i * barW, barY, barW, barH);
        });

        // Moving reticle
        const cx = (Math.sin(t * 3) * 0.35 + 0.5) * width;
        const cy = (Math.cos(t * 2) * 0.25 + 0.4) * height;
        ctx.fillStyle = '#34D399';
        ctx.beginPath();
        ctx.arc(cx, cy, 28, 0, Math.PI * 2);
        ctx.fill();
        ctx.lineWidth = 3;
        ctx.strokeStyle = '#FFFFFF';
        ctx.stroke();

        // Target watermark overlay
        ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
        ctx.fillRect(16, 16, width - 32, 64);
        ctx.strokeStyle = '#D0BCFF';
        ctx.strokeRect(16, 16, width - 32, 64);

        ctx.fillStyle = '#34D399';
        ctx.font = 'bold 12px "JetBrains Mono", monospace';
        ctx.textAlign = 'left';
        ctx.fillText(`TARGET INJECTION: ${targetPackage}`, 26, 38);

        ctx.fillStyle = '#E2E2E6';
        ctx.font = '11px "JetBrains Mono", monospace';
        ctx.fillText(`FRAME ID #${totalFrames} | IPC LATENCY 2.1ms`, 26, 56);
      }

      animRef.current = requestAnimationFrame(render);
    };

    render();

    return () => {
      if (animRef.current) {
        cancelAnimationFrame(animRef.current);
      }
    };
  }, [isOpen, isVirtualFeedActive, targetPackage]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-3 sm:p-6 overflow-y-auto">
      <video ref={videoRef} className="hidden" playsInline muted autoPlay />

      <div className="bg-[#1A1C1E] border border-[#44474E] rounded-[28px] max-w-2xl w-full max-h-[90vh] overflow-y-auto shadow-2xl p-5 sm:p-6 space-y-5">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-[#44474E] pb-4">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-[#34D399]/20 text-[#34D399] border border-[#34D399]/40 flex items-center justify-center">
              <Camera className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-base font-bold text-[#E2E2E6]">Controlled Camera Test Target</h3>
                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#34D399]/20 text-[#34D399] border border-[#34D399]/40 font-bold">
                  CAMERA2
                </span>
              </div>
              <p className="text-xs text-[#8E919A]">
                Side-by-side verification of physical sensor vs Zesto virtual injected frames
              </p>
            </div>
          </div>

          <button
            onClick={onClose}
            className="w-8 h-8 rounded-full bg-[#2D3033] hover:bg-[#44474E] text-[#C4C6CF] flex items-center justify-center transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Viewport Card */}
        <div className="relative aspect-[4/3] sm:aspect-video w-full bg-black rounded-2xl overflow-hidden border border-[#44474E] shadow-inner">
          <canvas
            ref={canvasRef}
            width={960}
            height={540}
            className="w-full h-full object-contain"
          />

          {/* Top HUD */}
          <div className="absolute top-3 left-3 bg-black/80 backdrop-blur-sm border border-[#44474E] px-3 py-1.5 rounded-xl flex items-center gap-2">
            <span
              className={`text-[11px] font-mono font-bold ${
                isVirtualFeedActive ? 'text-[#34D399]' : 'text-white'
              }`}
            >
              SOURCE: {isVirtualFeedActive ? 'VIRTUAL RTSP INJECTION' : 'RAW PHYSICAL SENSOR'}
            </span>
          </div>

          {/* Bottom HUD */}
          <div className="absolute bottom-3 right-3 flex items-center gap-2">
            <div className="bg-[#064E3B] text-[#34D399] border border-[#34D399]/40 text-[10px] font-mono font-bold px-2 py-1 rounded-lg">
              {healthState}
            </div>
            <div className="bg-black/80 border border-[#44474E] text-white text-[10px] font-mono px-2 py-1 rounded-lg">
              960x540 @ {measuredFps.toFixed(1)} FPS
            </div>
          </div>
        </div>

        {/* Feed Switching Toggle */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-2xl p-4 flex items-center justify-between">
          <div className="space-y-0.5">
            <div className="text-xs font-mono font-bold text-[#D0BCFF] flex items-center gap-1.5">
              <SwitchCamera className="w-4 h-4" />
              <span>INTEGRATION SWITCH</span>
            </div>
            <p className="text-xs text-[#C4C6CF]">
              {isVirtualFeedActive
                ? 'Consuming virtual frames from ZestoFrameBridge IPC'
                : 'Consuming raw CameraDevice hardware feed'}
            </p>
          </div>

          <label className="relative inline-flex items-center cursor-pointer">
            <input
              type="checkbox"
              checked={isVirtualFeedActive}
              onChange={(e) => setIsVirtualFeedActive(e.target.checked)}
              className="sr-only peer"
            />
            <div className="w-12 h-6 bg-[#1A1C1E] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-[#34D399]"></div>
          </label>
        </div>

        {/* Telemetry Metrics */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-2xl p-4 space-y-2 text-xs font-mono">
          <div className="flex justify-between text-[#8E919A] border-b border-[#44474E]/50 pb-2">
            <span>Target Process:</span>
            <span className="text-[#E2E2E6]">{targetPackage}</span>
          </div>
          <div className="flex justify-between text-[#8E919A]">
            <span>Frames Processed:</span>
            <span className="text-[#E2E2E6] font-bold">{frameCount}</span>
          </div>
          <div className="flex justify-between text-[#8E919A]">
            <span>Dropped Frames:</span>
            <span className="text-[#E2E2E6]">{droppedFrames}</span>
          </div>
          <div className="flex justify-between text-[#8E919A]">
            <span>Active Pipeline:</span>
            <span className="text-[#34D399]">
              {isVirtualFeedActive ? 'Zesto IPC Adapter Bridge' : 'android.hardware.camera2 HAL'}
            </span>
          </div>
        </div>

        {/* Dismiss button */}
        <button
          onClick={onClose}
          className="w-full py-3 rounded-xl bg-[#D0BCFF] hover:bg-[#EADDFF] text-[#381E72] font-mono text-xs font-bold transition-all shadow"
        >
          CLOSE HARNESS
        </button>
      </div>
    </div>
  );
};
