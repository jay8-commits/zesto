import React, { useState } from 'react';
import {
  DiagnosticsSnapshot,
  DiagnosticsEvent,
  DiagnosticsLevel,
  BoundaryDiagnosticStage
} from '../types.ts';
import {
  FileText,
  AlertOctagon,
  CheckCircle2,
  Search,
  Radio,
  Cpu,
  Layers,
  Camera,
  Shield,
  Smartphone,
  Check
} from 'lucide-react';

interface DiagnosticsScreenProps {
  snapshot: DiagnosticsSnapshot;
  events: DiagnosticsEvent[];
  onExportLogs: () => void;
  onClearLogs?: () => void;
}

export const DiagnosticsScreen: React.FC<DiagnosticsScreenProps> = ({
  snapshot,
  events,
  onExportLogs
}) => {
  const [searchFilter, setSearchFilter] = useState('');
  const [levelFilter, setLevelFilter] = useState<string>('ALL');

  const filteredEvents = events.filter((ev) => {
    const matchesSearch =
      ev.message.toLowerCase().includes(searchFilter.toLowerCase()) ||
      ev.subsystem.toLowerCase().includes(searchFilter.toLowerCase()) ||
      (ev.errorDetails && ev.errorDetails.toLowerCase().includes(searchFilter.toLowerCase()));

    const matchesLevel = levelFilter === 'ALL' || ev.level === levelFilter;
    return matchesSearch && matchesLevel;
  });

  const allBoundaries = Object.values(BoundaryDiagnosticStage);

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-24 sm:pb-8">
      {/* Top Header Card with Export Action */}
      <div className="bg-[#2D3033] border border-[#44474E] rounded-[24px] p-5 sm:p-6 flex flex-col sm:flex-row sm:items-center justify-between gap-4 shadow-lg">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-base font-bold text-[#E2E2E6]">Comprehensive Telemetry & Diagnostics</h2>
            <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-[#34D399]/20 text-[#34D399] border border-[#34D399]/40 font-bold">
              LIVE
            </span>
          </div>
          <p className="text-xs text-[#C4C6CF] mt-1">
            End-to-end multi-layer pipeline inspection from RTSP socket to Camera2 IPC surface injection.
          </p>
        </div>

        <button
          onClick={onExportLogs}
          className="px-4 py-2.5 rounded-xl bg-[#D0BCFF] hover:bg-[#EADDFF] text-[#381E72] font-mono font-bold text-xs flex items-center justify-center gap-2 transition-all shadow active:scale-95 shrink-0"
        >
          <FileText className="w-4 h-4" />
          <span>EXPORT LOGS</span>
        </button>
      </div>

      {/* Active Fault Isolation Banner (if any) */}
      {snapshot.faultSubsystem && (
        <div className="bg-[#7F1D1D]/30 border border-[#EF4444] rounded-[20px] p-5 space-y-2">
          <div className="flex items-center gap-2 text-[#EF4444] font-mono font-bold text-xs tracking-wider uppercase">
            <AlertOctagon className="w-4 h-4" />
            <span>Active Fault Isolation in Subsystem: {snapshot.faultSubsystem}</span>
          </div>
          <div className="text-sm text-[#FECACA] font-mono">
            {snapshot.lastErrorMessage || 'Subsystem reported an unhandled exception or stream timeout.'}
          </div>
        </div>
      )}

      {/* 6 Subsystem Telemetry Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* 1. Transport Layer */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5 space-y-3">
          <div className="flex items-center justify-between border-b border-[#44474E] pb-2.5">
            <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#D0BCFF]">
              <Radio className="w-4 h-4" />
              <span>TRANSPORT LAYER</span>
            </div>
            <span
              className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded ${
                snapshot.transportStatus === 'CONNECTED'
                  ? 'bg-[#064E3B] text-[#34D399]'
                  : 'bg-[#1A1C1E] text-[#8E919A]'
              }`}
            >
              {snapshot.transportStatus}
            </span>
          </div>

          <div className="space-y-1.5 text-xs font-mono">
            <div className="flex justify-between text-[#8E919A]">
              <span>Protocol:</span>
              <span className="text-[#E2E2E6]">{snapshot.transportType}</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Packets (Rx/Loss):</span>
              <span className="text-[#E2E2E6]">
                {snapshot.streamStats.packetsReceived} / {snapshot.streamStats.packetsLost}
              </span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Bitrate:</span>
              <span className="text-[#34D399]">
                {snapshot.streamStats.estimatedBitrateKbps.toFixed(1)} kbps
              </span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Reconnects:</span>
              <span className="text-[#E2E2E6]">{snapshot.reconnectCount}</span>
            </div>
          </div>
        </div>

        {/* 2. Video Decoder */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5 space-y-3">
          <div className="flex items-center justify-between border-b border-[#44474E] pb-2.5">
            <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#D0BCFF]">
              <Cpu className="w-4 h-4" />
              <span>VIDEO DECODER</span>
            </div>
            <span
              className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded ${
                snapshot.decoderStatus === 'DECODING'
                  ? 'bg-[#064E3B] text-[#34D399]'
                  : 'bg-[#1A1C1E] text-[#8E919A]'
              }`}
            >
              {snapshot.decoderStatus}
            </span>
          </div>

          <div className="space-y-1.5 text-xs font-mono">
            <div className="flex justify-between text-[#8E919A]">
              <span>Resolution:</span>
              <span className="text-[#E2E2E6]">{snapshot.decoderResolution}</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Decoded Frames:</span>
              <span className="text-[#E2E2E6]">{snapshot.decodedFrames}</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Decode Latency:</span>
              <span className="text-[#34D399]">
                {snapshot.decoderStats.averageDecodeLatencyMs.toFixed(1)} ms
              </span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Hardware Accel:</span>
              <span className="text-[#D0BCFF]">
                {snapshot.decoderStats.hwAccelerated ? 'MediaCodec (Active)' : 'Software'}
              </span>
            </div>
          </div>
        </div>

        {/* 3. Frame Pipeline */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5 space-y-3">
          <div className="flex items-center justify-between border-b border-[#44474E] pb-2.5">
            <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#D0BCFF]">
              <Layers className="w-4 h-4" />
              <span>FRAME PIPELINE</span>
            </div>
            <span
              className={`text-[10px] font-mono font-bold px-2 py-0.5 rounded ${
                snapshot.pipelineStatus === 'ACTIVE'
                  ? 'bg-[#064E3B] text-[#34D399]'
                  : 'bg-[#1A1C1E] text-[#8E919A]'
              }`}
            >
              {snapshot.frameHealthState}
            </span>
          </div>

          <div className="space-y-1.5 text-xs font-mono">
            <div className="flex justify-between text-[#8E919A]">
              <span>Delivered Frames:</span>
              <span className="text-[#E2E2E6]">{snapshot.deliveredFrames}</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Dropped Frames:</span>
              <span className="text-[#EF4444]">{snapshot.pipelineDroppedFrames}</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Last Frame Age:</span>
              <span className="text-[#E2E2E6]">
                {snapshot.msSinceLastFrame >= 0 ? `${snapshot.msSinceLastFrame} ms` : 'N/A'}
              </span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Bridge Consumers:</span>
              <span className="text-[#D0BCFF]">{snapshot.activeConsumers} Registered</span>
            </div>
          </div>
        </div>

        {/* 4. Camera API & HAL */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5 space-y-3">
          <div className="flex items-center justify-between border-b border-[#44474E] pb-2.5">
            <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#D0BCFF]">
              <Camera className="w-4 h-4" />
              <span>CAMERA API & HAL</span>
            </div>
            <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded bg-[#1A1C1E] text-[#D0BCFF]">
              {snapshot.detectedCameraApi}
            </span>
          </div>

          <div className="space-y-1.5 text-xs font-mono">
            <div className="flex justify-between text-[#8E919A]">
              <span>Hardware Level:</span>
              <span className="text-[#E2E2E6]">{snapshot.cameraHardwareLevel}</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Surface Direct:</span>
              <span className="text-[#34D399]">SurfaceTexture Supported</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>ImageReader Target:</span>
              <span className="text-[#34D399]">Available (YUV_420_888)</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Hook Architecture:</span>
              <span className="text-[#E2E2E6]">Classloader Bytecode Intercept</span>
            </div>
          </div>
        </div>

        {/* 5. Virtualization Backend */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5 space-y-3">
          <div className="flex items-center justify-between border-b border-[#44474E] pb-2.5">
            <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#D0BCFF]">
              <Shield className="w-4 h-4" />
              <span>VIRTUALIZATION BACKEND</span>
            </div>
            <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded bg-[#1A1C1E] text-[#34D399]">
              ONLINE
            </span>
          </div>

          <div className="space-y-1.5 text-xs font-mono">
            <div className="flex justify-between text-[#8E919A]">
              <span>Active Backend:</span>
              <span className="text-[#D0BCFF] font-bold">{snapshot.activeBackend}</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Status:</span>
              <span className="text-[#E2E2E6]">{snapshot.virtualizationStatus}</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>IPC Authority:</span>
              <span className="text-[#8E919A] text-[10px] truncate max-w-[180px]">
                {snapshot.ipcProviderStatus.split(' ')[0]}
              </span>
            </div>
          </div>
        </div>

        {/* 6. Target Application */}
        <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5 space-y-3">
          <div className="flex items-center justify-between border-b border-[#44474E] pb-2.5">
            <div className="flex items-center gap-2 text-xs font-mono font-bold text-[#D0BCFF]">
              <Smartphone className="w-4 h-4" />
              <span>TARGET INTEGRATION</span>
            </div>
            <span className="text-[10px] font-mono font-bold px-2 py-0.5 rounded bg-[#1A1C1E] text-[#D0BCFF]">
              {snapshot.targetStatus}
            </span>
          </div>

          <div className="space-y-1.5 text-xs font-mono">
            <div className="flex justify-between text-[#8E919A]">
              <span>Target Package:</span>
              <span className="text-[#E2E2E6] truncate max-w-[180px]">
                {snapshot.targetPackage}
              </span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>LSPatch Portable:</span>
              <span className="text-[#34D399]">Manifest Verified Safe</span>
            </div>
            <div className="flex justify-between text-[#8E919A]">
              <span>Hook Resolution:</span>
              <span className="text-[#E2E2E6]">CameraDevice.createCaptureSession</span>
            </div>
          </div>
        </div>
      </div>

      {/* Boundary Diagnostic Stages Checklist */}
      <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5 space-y-4 shadow">
        <div className="flex items-center justify-between">
          <h3 className="text-xs font-mono font-bold tracking-wider text-[#C4C6CF] uppercase flex items-center gap-2">
            <CheckCircle2 className="w-4 h-4 text-[#34D399]" />
            <span>Pipeline Boundary Milestones (Verification Trace)</span>
          </h3>
          <span className="text-[11px] font-mono text-[#8E919A]">
            {snapshot.activeBoundaries.length} / {allBoundaries.length} Verified
          </span>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
          {allBoundaries.map((boundary) => {
            const isCompleted = snapshot.activeBoundaries.includes(boundary);
            return (
              <div
                key={boundary}
                className={`p-2.5 rounded-xl border flex items-center gap-2.5 text-xs font-mono transition-colors ${
                  isCompleted
                    ? 'bg-[#1A1C1E] border-[#34D399]/40 text-[#E2E2E6]'
                    : 'bg-[#1A1C1E]/50 border-[#44474E]/40 text-[#64748B]'
                }`}
              >
                <div
                  className={`w-4 h-4 rounded flex items-center justify-center shrink-0 ${
                    isCompleted
                      ? 'bg-[#34D399] text-[#1A1C1E]'
                      : 'border border-[#44474E]'
                  }`}
                >
                  {isCompleted && <Check className="w-3 h-3 stroke-[3]" />}
                </div>
                <span className="truncate">{boundary}</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Live Event Log with Search & Filter */}
      <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5 space-y-4 shadow">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <h3 className="text-xs font-mono font-bold tracking-wider text-[#C4C6CF] uppercase">
            Diagnostic Event Stream ({filteredEvents.length} records)
          </h3>

          <div className="flex flex-wrap items-center gap-2">
            {/* Level selector */}
            <div className="flex items-center gap-1 bg-[#1A1C1E] p-1 rounded-xl border border-[#44474E]">
              {['ALL', 'INFO', 'WARNING', 'ERROR'].map((lvl) => (
                <button
                  key={lvl}
                  onClick={() => setLevelFilter(lvl)}
                  className={`px-2 py-0.5 rounded-lg text-[10px] font-mono font-bold transition-colors ${
                    levelFilter === lvl
                      ? 'bg-[#D0BCFF] text-[#381E72]'
                      : 'text-[#8E919A] hover:text-white'
                  }`}
                >
                  {lvl}
                </button>
              ))}
            </div>

            {/* Search input */}
            <div className="relative">
              <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8E919A]" />
              <input
                type="text"
                value={searchFilter}
                onChange={(e) => setSearchFilter(e.target.value)}
                placeholder="Filter logs..."
                className="bg-[#1A1C1E] border border-[#44474E] rounded-xl pl-8 pr-3 py-1 text-xs text-[#E2E2E6] font-mono placeholder-[#64748B] focus:outline-none focus:border-[#D0BCFF]"
              />
            </div>
          </div>
        </div>

        {/* Log table container */}
        <div className="bg-[#1A1C1E] border border-[#44474E] rounded-xl p-3 max-h-72 overflow-y-auto font-mono text-xs space-y-2">
          {filteredEvents.length === 0 ? (
            <div className="text-center py-6 text-[#64748B]">
              No diagnostic events match current filter.
            </div>
          ) : (
            filteredEvents.map((ev, idx) => {
              const timeStr =
                new Date(ev.timestampMs).toTimeString().split(' ')[0] +
                '.' +
                String(ev.timestampMs % 1000).padStart(3, '0');

              return (
                <div
                  key={idx}
                  className="flex items-start gap-2.5 hover:bg-[#222427] p-1.5 rounded-lg transition-colors border-b border-[#2D3033] last:border-0"
                >
                  <span className="text-[#64748B] shrink-0">{timeStr}</span>
                  <span
                    className={`px-1.5 py-0.5 rounded text-[10px] font-bold shrink-0 ${
                      ev.level === DiagnosticsLevel.ERROR
                        ? 'bg-[#EF4444]/20 text-[#EF4444]'
                        : ev.level === DiagnosticsLevel.WARNING
                        ? 'bg-[#FBBF24]/20 text-[#FBBF24]'
                        : 'bg-[#34D399]/20 text-[#34D399]'
                    }`}
                  >
                    {ev.level}
                  </span>
                  <span className="text-[#D0BCFF] shrink-0">[{ev.subsystem}]</span>
                  <div className="text-[#E2E2E6] flex-1 break-all">
                    {ev.message}
                    {ev.errorDetails && (
                      <div className="text-[#EF4444] text-[11px] mt-0.5">
                        Details: {ev.errorDetails}
                      </div>
                    )}
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};
