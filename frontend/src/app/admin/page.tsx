// frontend/src/app/admin/page.tsx
import { AdminDashboard } from "@/components/admin/AdminDashboard";

export default function AdminPage() {
  return (
    <>
      <h1 className="mb-6 text-2xl font-bold">Administration</h1>
      <AdminDashboard />
    </>
  );
}
