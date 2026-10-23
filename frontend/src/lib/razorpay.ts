import type { RazorpayCheckout } from './types'

/** The subset of Razorpay Checkout (checkout.js) TurfUp uses. */
interface RazorpayInstance {
  open(): void
  on(event: 'payment.failed', handler: () => void): void
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => RazorpayInstance
  }
}

const SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js'
let loading: Promise<void> | null = null

/** Loads checkout.js once. Rejects if it cannot be fetched (offline, blocked). */
function loadCheckout(): Promise<void> {
  if (window.Razorpay) return Promise.resolve()
  loading ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      loading = null
      script.remove()
      reject(new Error('checkout.js failed to load'))
    }
    document.head.appendChild(script)
  })
  return loading
}

/**
 * Opens Razorpay Checkout for an order. {@code onPaid} runs when the player finishes paying; the booking is
 * confirmed by the server from Razorpay's webhook, so the caller then waits for the booking to change.
 * {@code onClosed} runs if the player closes the window without paying.
 */
export async function openCheckout(
  checkout: RazorpayCheckout,
  { onPaid, onClosed }: { onPaid: () => void; onClosed: () => void },
): Promise<void> {
  await loadCheckout()
  const rzp = new window.Razorpay!({
    key: checkout.keyId,
    order_id: checkout.orderId,
    amount: checkout.amount,
    currency: checkout.currency,
    name: checkout.name,
    description: checkout.description,
    prefill: { name: checkout.prefillName, contact: checkout.prefillContact },
    theme: { color: '#13291d' },
    handler: onPaid,
    modal: { ondismiss: onClosed, confirm_close: true },
  })
  rzp.open()
}
