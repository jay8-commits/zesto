import React, { useState } from 'react';
import {
  TargetProfile,
  CameraVirtualizationStatus,
  ZestoUiState
} from '../types.ts';
import {
  ShieldAlert,
  Play,
  Square,
  FlaskConical,
  BookOpen,
  Search,
  Lock,
  Sparkles
} from 'lucide-react';

interface TargetCompatibilityScreenProps {
  uiState: ZestoUiState;
  onSelectProfile: (profile: TargetProfile) => void;
  onToggleService: () => void;
  onOpenTestHarness: () => void;
  onOpenModuleGuide: () => void;
  onRunProfileTest: (profile: TargetProfile) => void;
}

export const TargetCompatibilityScreen: React.FC<TargetCompatibilityScreenProps> = ({
  uiState,
  onSelectProfile,
  onToggleService,
  onOpenTestHarness,
  onOpenModuleGuide,
  onRunProfileTest
}) => {
  const [search, setSearch] = useState('');
  const [filterCategory, setFilterCategory] = useState<'ALL' | 'VERIFIED' | 'ROOT' | 'NON_ROOT'>('ALL');

  const filteredProfiles = uiState.targetProfiles.filter((p) => {
    const matchesSearch =
      p.appName.toLowerCase().includes(search.toLowerCase()) ||
      p.packageName.toLowerCase().includes(search.toLowerCase()) ||
      p.diagnosticInfo.toLowerCase().includes(search.toLowerCase());

    if (filterCategory === 'VERIFIED') {
      return matchesSearch && (p.testStatus === CameraVirtualizationStatus.SUPPORTED || p.testStatus === CameraVirtualizationStatus.ACTIVE);
    }
    if (filterCategory === 'ROOT') {
      return matchesSearch && p.requiresRoot;
    }
    if (filterCategory === 'NON_ROOT') {
      return matchesSearch && !p.requiresRoot;
    }
    return matchesSearch;
  });

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-24 sm:pb-8">
      {/* Truth in Engineering Banner */}
      <div className="bg-[#2D3033] border border-[#D0BCFF]/40 rounded-[24px] p-5 sm:p-6 shadow-lg relative overflow-hidden">
        <div className="flex items-start gap-4">
          <div className="w-10 h-10 rounded-2xl bg-[#D0BCFF]/10 border border-[#D0BCFF]/30 flex items-center justify-center shrink-0 text-[#D0BCFF]">
            <ShieldAlert className="w-5 h-5" />
          </div>
          <div className="space-y-1.5 flex-1">
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-mono font-bold tracking-widest px-2 py-0.5 rounded bg-[#D0BCFF]/20 text-[#D0BCFF] border border-[#D0BCFF]/30 uppercase">
                Truth-in-Engineering Mandate
              </span>
            </div>
            <h2 className="text-base font-bold text-[#E2E2E6]">
              Stage 3 Virtualization Verification Matrix
            </h2>
            <p className="text-xs text-[#C4C6CF] leading-relaxed">
              Zesto maintains absolute engineering truth: compatibility with target third-party apps is marked{' '}
              <span className="font-mono text-[#D0BCFF]">NOT TESTED</span> until physically verified inside target bytecode/runtime process via LSPatch portable injection or LSPosed module hooks.
            </p>
          </div>
        </div>
      </div>

      {/* Service Control & Diagnostic Harness Launch Card */}
      <div className="bg-[#2D3033] border border-[#44474E] rounded-[24px] p-5 sm:p-6 shadow-lg space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-xs font-mono font-bold tracking-wider text-[#C4C6CF] uppercase">
            Service Control & Test Harness
          </h3>
          <span className="text-xs font-mono text-[#34D399] flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-full bg-[#34D399] animate-pulse" />
            <span>IPC Provider Active</span>
          </span>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          {/* Start/Stop Service Button */}
          <button
            onClick={onToggleService}
            className={`px-4 py-3 rounded-2xl font-mono text-xs font-bold flex items-center justify-center gap-2 transition-all shadow-md active:scale-95 ${
              uiState.isServiceRunning
                ? 'bg-[#7F1D1D] hover:bg-[#991B1B] text-white border border-[#EF4444]/40'
                : 'bg-[#D0BCFF] hover:bg-[#EADDFF] text-[#381E72]'
            }`}
          >
            {uiState.isServiceRunning ? (
              <>
                <Square className="w-4 h-4 fill-current" />
                <span>STOP SERVICE</span>
              </>
            ) : (
              <>
                <Play className="w-4 h-4 fill-current" />
                <span>START SERVICE</span>
              </>
            )}
          </button>

          {/* Launch Test Harness Button */}
          <button
            onClick={onOpenTestHarness}
            className="px-4 py-3 rounded-2xl bg-[#1A1C1E] hover:bg-[#222427] text-[#34D399] border border-[#34D399]/40 font-mono text-xs font-bold flex items-center justify-center gap-2 transition-all shadow-md active:scale-95"
          >
            <FlaskConical className="w-4 h-4" />
            <span>TEST HARNESS</span>
          </button>

          {/* LSPatch Module Guide Button */}
          <button
            onClick={onOpenModuleGuide}
            className="px-4 py-3 rounded-2xl bg-[#1A1C1E] hover:bg-[#222427] text-[#D0BCFF] border border-[#44474E] font-mono text-xs font-bold flex items-center justify-center gap-2 transition-all shadow-md active:scale-95"
          >
            <BookOpen className="w-4 h-4" />
            <span>MODULE GUIDE</span>
          </button>
        </div>
      </div>

      {/* Target Applications Search & Filter */}
      <div className="space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-bold text-[#E2E2E6]">
              Target Application Profiles ({filteredProfiles.length})
            </h3>
            <p className="text-xs text-[#8E919A]">
              Select target application profile to bind Camera2/CameraX virtualization backend
            </p>
          </div>

          <div className="flex items-center gap-2">
            {/* Category Chips */}
            <div className="flex items-center gap-1 bg-[#2D3033] p-1 rounded-xl border border-[#44474E]">
              {[
                { id: 'ALL', label: 'All' },
                { id: 'NON_ROOT', label: 'Non-Root' },
                { id: 'ROOT', label: 'Root Req.' }
              ].map((cat) => (
                <button
                  key={cat.id}
                  onClick={() => setFilterCategory(cat.id as any)}
                  className={`px-2.5 py-1 rounded-lg text-xs font-mono transition-colors ${
                    filterCategory === cat.id
                      ? 'bg-[#D0BCFF] text-[#381E72] font-bold'
                      : 'text-[#8E919A] hover:text-white'
                  }`}
                >
                  {cat.label}
                </button>
              ))}
            </div>

            {/* Search */}
            <div className="relative">
              <Search className="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-[#8E919A]" />
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search app..."
                className="bg-[#2D3033] border border-[#44474E] rounded-xl pl-8 pr-3 py-1.5 text-xs text-[#E2E2E6] font-mono placeholder-[#64748B] focus:outline-none focus:border-[#D0BCFF]"
              />
            </div>
          </div>
        </div>

        {/* Profiles List */}
        <div className="space-y-3">
          {filteredProfiles.map((profile) => {
            const isSelected = uiState.selectedTargetProfile?.id === profile.id;
            const isRoot = profile.requiresRoot;

            return (
              <div
                key={profile.id}
                onClick={() => onSelectProfile(profile)}
                className={`bg-[#2D3033] border rounded-[20px] p-5 cursor-pointer transition-all ${
                  isSelected
                    ? 'border-[#D0BCFF] bg-[#2D3033] shadow-lg ring-1 ring-[#D0BCFF]/50'
                    : 'border-[#44474E] hover:border-[#8E919A]'
                }`}
              >
                <div className="flex flex-col sm:flex-row sm:items-start justify-between gap-3">
                  {/* Left info */}
                  <div className="flex items-start gap-3.5">
                    {/* Radio circle */}
                    <div
                      className={`w-5 h-5 rounded-full mt-0.5 border-2 flex items-center justify-center shrink-0 transition-colors ${
                        isSelected
                          ? 'border-[#D0BCFF] bg-[#381E72]'
                          : 'border-[#64748B]'
                      }`}
                    >
                      {isSelected && (
                        <div className="w-2 h-2 rounded-full bg-[#D0BCFF]" />
                      )}
                    </div>

                    <div className="space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-sm font-bold text-[#E2E2E6]">
                          {profile.appName}
                        </span>
                        <span className="text-[11px] font-mono text-[#8E919A]">
                          {profile.packageName}
                        </span>
                      </div>

                      <div className="text-xs text-[#C4C6CF] leading-relaxed pt-0.5">
                        {profile.diagnosticInfo}
                      </div>

                      <div className="flex flex-wrap items-center gap-2 pt-2 text-[11px] font-mono">
                        <span className="px-2 py-0.5 rounded bg-[#1A1C1E] text-[#D0BCFF] border border-[#44474E]">
                          Backend: {profile.supportedBackend}
                        </span>
                        <span className="px-2 py-0.5 rounded bg-[#1A1C1E] text-[#8E919A] border border-[#44474E]">
                          API: {profile.cameraApi}
                        </span>
                        <span className="px-2 py-0.5 rounded bg-[#1A1C1E] text-[#C4C6CF] border border-[#44474E]">
                          {profile.integrationMechanism.split('/')[0]}
                        </span>
                      </div>
                    </div>
                  </div>

                  {/* Right badges & action */}
                  <div className="flex flex-row sm:flex-col items-end justify-between gap-2 shrink-0 pt-2 sm:pt-0">
                    <div className="flex items-center gap-2">
                      {isRoot && (
                        <span className="px-2 py-0.5 rounded bg-[#EF4444]/20 border border-[#EF4444]/40 text-[#EF4444] text-[10px] font-mono font-bold flex items-center gap-1">
                          <Lock className="w-3 h-3" /> ROOT
                        </span>
                      )}
                      <span
                        className={`px-2.5 py-0.5 rounded text-[10px] font-mono font-bold uppercase ${
                          profile.testStatus === CameraVirtualizationStatus.ACTIVE ||
                          profile.testStatus === CameraVirtualizationStatus.SUPPORTED
                            ? 'bg-[#064E3B] text-[#34D399] border border-[#34D399]/40'
                            : profile.testStatus === CameraVirtualizationStatus.TESTING
                            ? 'bg-[#78350F] text-[#FBBF24] border border-[#FBBF24]/40 animate-pulse'
                            : 'bg-[#1A1C1E] text-[#8E919A] border border-[#44474E]'
                        }`}
                      >
                        {profile.testStatus}
                      </span>
                    </div>

                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onRunProfileTest(profile);
                      }}
                      className="px-3 py-1.5 rounded-xl bg-[#1A1C1E] hover:bg-[#35393E] text-[#D0BCFF] border border-[#44474E] text-xs font-mono font-semibold flex items-center gap-1.5 transition-colors"
                    >
                      <Sparkles className="w-3.5 h-3.5" />
                      <span>Verify</span>
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
