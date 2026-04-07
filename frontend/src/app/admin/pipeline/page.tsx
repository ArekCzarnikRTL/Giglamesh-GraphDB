// frontend/src/app/admin/pipeline/page.tsx
import { PipelinePanel } from "@/components/admin/PipelinePanel";

export default function AdminPipelinePage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Pipeline</h1>
      <PipelinePanel />
    </>
  );
}
