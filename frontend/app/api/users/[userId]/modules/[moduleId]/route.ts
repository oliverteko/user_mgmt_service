import { cookies } from "next/headers"

// Assign a module to a user (backend PUT /users/{userId}/modules/{moduleId}).
export async function PUT(
  _req: Request,
  { params }: { params: Promise<{ userId: string; moduleId: string }> }
) {
  const token = (await cookies()).get("jwt")?.value

  if (!token) {
    return Response.json({ error: "Unauthorized" }, { status: 401 })
  }

  const { userId, moduleId } = await params

  const res = await fetch(
    `${process.env.INTERNAL_API_URL}/users/${encodeURIComponent(userId)}/modules/${encodeURIComponent(moduleId)}`,
    {
      method: "PUT",
      headers: {
        Authorization: `Bearer ${token}`,
      },
    }
  )

  // Pass the backend's status and body through: 200 assigned, 400 malformed id,
  // 403 not allowed, 404 user/module not found, 503 module_service unavailable
  return new Response(await res.text(), {
    status: res.status,
    headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" },
  })
}
