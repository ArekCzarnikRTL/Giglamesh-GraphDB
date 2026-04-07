// frontend/src/app/admin/config/page.tsx
import { ConfigEditor } from "@/components/admin/ConfigEditor";

export default function AdminConfigPage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Konfiguration</h1>
      <ConfigEditor />
    </>
  );
}
