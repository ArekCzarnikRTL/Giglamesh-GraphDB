import Link from "next/link";
import { CollectionSelector } from "@/components/documents/CollectionSelector";
import { buttonVariants } from "@/components/ui/button";

export default function DocumentsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="container mx-auto p-6 space-y-6">
      <header className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-4">
          <h1 className="text-2xl font-bold">
            <Link href="/documents">Dokumente</Link>
          </h1>
          <CollectionSelector />
        </div>
        <Link href="/documents/upload" className={buttonVariants()}>
          Hochladen
        </Link>
      </header>
      <main>{children}</main>
    </div>
  );
}
