import { NextResponse } from "next/server"

export async function POST(req: Request) {
  try {
    const body = await req.json()

    const res = await fetch(`${process.env.INTERNAL_API_URL}/users/register`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    })

    if (!res.ok) {
      return NextResponse.json(
          { message: "Registration failed" },
          { status: res.status }
      )
    }

    return NextResponse.json({ success: true })
  } catch (err) {
    return NextResponse.json(
        { message: "Server error" },
        { status: 500 }
    )
  }
}
