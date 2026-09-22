import { cookies } from "next/headers"

// Modules that can be assigned (backend GET /modules, which asks the module_service).
export async function GET() {
  const token = (await cookies()).get("jwt")?.value

  if (!token) {
    return Response.json({ error: "Unauthorized" }, { status: 401 })
  }

  const res = await fetch(`${process.env.INTERNAL_API_URL}/modules`, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${token}`,
    },
  })

  // Pass the backend's status and body through (e.g. 503 if the module_service is down)
  return new Response(await res.text(), {
    status: res.status,
    headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" },
  })
}
