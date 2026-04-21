import { DocumentDetail } from "@/components/documents/DocumentDetail";

interface Props {
  params: { id: string[] };
}

export default function DocumentDetailPage({ params }: Props) {
  return <DocumentDetail documentId={params.id.join("/")} />;
}
