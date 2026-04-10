// frontend/src/app/admin/ontologies/page.tsx
import { OntologyManager } from "@/components/admin/OntologyManager";

export default function AdminOntologiesPage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Ontologie-Verwaltung</h1>
      <OntologyManager />
    </>
  );
}
