// frontend/src/app/admin/collections/page.tsx
import { CollectionManager } from "@/components/admin/CollectionManager";

export default function AdminCollectionsPage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Collection-Verwaltung</h1>
      <CollectionManager />
    </>
  );
}
