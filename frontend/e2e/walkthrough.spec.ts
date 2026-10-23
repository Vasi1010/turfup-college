import { expect, test, type Locator, type Page } from '@playwright/test'

const SHOTS = process.env.SHOTS ?? 'test-results/shots'
const run = String(Date.now()).slice(-8)
const player = { name: 'Rahul Patil', phone: `9${run}1`, password: 'player-pass-1' }
const owner2 = { name: 'Sana Qureshi', phone: `7${run}2`, password: 'owner-pass-12' }
const demoOwner = { phone: '8888888888', password: 'owner-demo-123' }
const admin = { phone: '9999999999', password: 'admin-demo-123' }

/** IST calendar date `days` from now, "YYYY-MM-DD". */
function istDay(days: number): string {
  const d = new Date(Date.now() + 330 * 60_000 + days * 86_400_000)
  return d.toISOString().slice(0, 10)
}

/** Player bookings use a window of days unique to this run, so repeated runs never collide. */
const BASE = 8 + 6 * (Math.floor(Date.now() / 1000) % 400)
const day = (n: number) => istDay(BASE + n)

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** The time label ("6–7 PM") of the n-th free slot in one court's column of the owner's day view. */
async function freeSlot(court: Locator, n: number): Promise<string> {
  const button = court.getByRole('button', { name: /Add$/ }).nth(n)
  return (await button.locator('span').first().textContent())!.trim()
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, animations: 'disabled' })
}

async function login(page: Page, phone: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('Mobile number').fill(phone)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('main').getByRole('button', { name: 'Log in' }).click()
  await expect(page).not.toHaveURL(/\/login/)
}

async function logout(page: Page) {
  await page.goto('/account')
  await page.getByRole('button', { name: 'Log out' }).click()
  await expect(page).toHaveURL(/\/$/)
}

async function holdAndPay(page: Page, venueId: string, date: string, slot: RegExp) {
  await page.goto(`/venues/${venueId}?date=${date}`)
  await page.getByRole('button', { name: slot }).click()
  await page.getByRole('button', { name: 'Hold this slot' }).click()
  await expect(page.getByText('hold left')).toBeVisible()
  await page.getByRole('button', { name: /^Pay ₹200/ }).click()
  await expect(page.getByText('Booked', { exact: true })).toBeVisible()
}

test('full flow: player, owner, admin', async ({ page }) => {
  let venueId = ''

  await test.step('explore and pick Kick Arena', async () => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: /Find a court/ })).toBeVisible()
    // The demo venue has a football/cricket turf and two badminton courts.
    const card = page.getByRole('link', { name: /Kick Arena/ })
    await expect(card.getByText('Badminton ×2')).toBeVisible()
    await expect(card.getByText('₹300')).toBeVisible()
    await shot(page, '01-explore')
    await page.getByRole('button', { name: /^Badminton/ }).click()
    await expect(page.getByRole('link', { name: /Kick Arena/ }).getByText('₹300')).toBeVisible()
    await page.getByRole('button', { name: /^All sports/ }).click()
    await page.getByRole('link', { name: /Kick Arena/ }).click()
    await expect(page.getByRole('heading', { name: 'Kick Arena' })).toBeVisible()
    venueId = page.url().match(/venues\/(\d+)/)![1]
    await page.getByRole('tablist', { name: 'Slots' }).getByRole('tab').nth(3).click()
    await expect(page.getByText('Evening', { exact: true })).toBeVisible()
    await shot(page, '02-turf-slots')
  })

  await test.step('select 8–10 PM, log in on the way, hold and pay', async () => {
    await page.goto(`/venues/${venueId}?date=${day(0)}`)
    await page.getByRole('button', { name: /^8–9 PM/ }).click()
    await page.getByRole('button', { name: '+ 1 hr' }).click()
    await expect(page.getByText('8–10 PM')).toBeVisible()
    await expect(page.getByText('₹2,000')).toBeVisible()
    await shot(page, '03-selection')
    await page.getByRole('button', { name: 'Log in to hold' }).click()

    await page.getByRole('tab', { name: 'Create account' }).click()
    await page.getByLabel('Your name').fill(player.name)
    await page.getByLabel('Mobile number').fill(player.phone)
    await page.getByLabel('Password').fill(player.password)
    await shot(page, '04-register')
    await page.getByRole('main').getByRole('button', { name: 'Create account' }).click()

    await expect(page.getByText('8–10 PM')).toBeVisible() // selection survived the login
    await page.getByRole('button', { name: 'Hold this slot' }).click()
    await expect(page.getByText('hold left')).toBeVisible()
    await expect(page.getByText('₹1,800')).toBeVisible() // balance at the venue
    await expect(page.getByText(/Main turf/)).toBeVisible()
    await shot(page, '05-hold-countdown')
    await page.getByRole('button', { name: 'Pay ₹200' }).click()
    await expect(page.getByText('Booked', { exact: true })).toBeVisible()
    await shot(page, '06-ticket')
  })

  await test.step('a second hold while one is open is refused with a clear message', async () => {
    await page.goto(`/venues/${venueId}?date=${day(1)}`)
    await page.getByRole('button', { name: /^6–7 PM/ }).click()
    await page.getByRole('button', { name: 'Hold this slot' }).click()
    await expect(page.getByText('hold left')).toBeVisible()

    await page.goto(`/venues/${venueId}?date=${day(2)}`)
    await page.getByRole('button', { name: /^7–8 PM/ }).click()
    await page.getByRole('button', { name: 'Hold this slot' }).click()
    await expect(page.getByText('You already have a slot on hold')).toBeVisible()
    await shot(page, '07-active-hold-error')

    await page.goto('/bookings')
    await page.getByRole('button', { name: /Pay now · \d:\d\d left/ }).click()
    await page.getByRole('button', { name: 'Pay ₹200' }).click()
    await expect(page.getByText('Booked', { exact: true })).toBeVisible()
    await holdAndPay(page, venueId, day(2), /^7–8 PM/)
  })

  await test.step('cancel: first refund of the month, then no refund', async () => {
    await page.goto('/bookings')
    await expect(page.getByRole('article')).toHaveCount(3)
    await shot(page, '08-my-bookings')

    const first = page.getByRole('article').filter({ hasText: '8–10 PM' })
    await first.getByRole('button', { name: 'Cancel booking' }).click()
    const sheet = page.getByRole('dialog')
    await expect(sheet.getByText(/₹200 back, uses your 1 refund for \w+/)).toBeVisible()
    await shot(page, '09-cancel-refund')
    await sheet.getByRole('button', { name: 'Yes, cancel' }).click()
    await expect(page.getByText(/Booking cancelled\. ₹200 back/)).toBeVisible()

    const third = page.getByRole('article').filter({ hasText: '7–8 PM' })
    await third.getByRole('button', { name: 'Cancel booking' }).click()
    await expect(page.getByRole('dialog').getByText('No refund: already used this month')).toBeVisible()
    await shot(page, '10-cancel-no-refund')
    await page.getByRole('dialog').getByRole('button', { name: 'Keep booking' }).click()

    await page.getByRole('tab', { name: /Past/ }).click()
    await expect(page.getByText('You cancelled')).toBeVisible()
    await expect(page.getByText('₹200 refunded')).toBeVisible()
  })

  await test.step('release a hold', async () => {
    await page.goto(`/venues/${venueId}?date=${day(3)}`)
    await page.getByRole('button', { name: /^9–10 PM/ }).click()
    await page.getByRole('button', { name: 'Hold this slot' }).click()
    await page.getByRole('button', { name: 'Release this slot' }).click()
    await expect(page.getByText('Slot released.')).toBeVisible()
    await expect(page.getByRole('button', { name: /^9–10 PM/ })).toBeEnabled()
  })

  await test.step('badminton: any free court spreads bookings over the courts', async () => {
    await page.goto(`/venues/${venueId}?sport=badminton&date=${day(5)}`)
    await expect(page.getByRole('button', { name: 'Any free court' })).toHaveAttribute('aria-pressed', 'true')
    await expect(page.getByRole('button', { name: /^8–9 PM.*2 courts free/ })).toBeVisible()
    await shot(page, '10b-any-court')
    for (const court of ['Badminton 1', 'Badminton 2']) {
      await page.goto(`/venues/${venueId}?sport=badminton&date=${day(5)}`)
      await page.getByRole('button', { name: /^8–9 PM/ }).click()
      await page.getByRole('button', { name: 'Hold this slot' }).click()
      await expect(page.getByText(new RegExp(court))).toBeVisible()
      await page.getByRole('button', { name: 'Pay ₹100' }).click()
      await expect(page.getByText('Booked', { exact: true })).toBeVisible()
    }
    await page.goto(`/venues/${venueId}?sport=badminton&date=${day(5)}`)
    await expect(page.getByRole('button', { name: /^8–9 PM/ })).toBeDisabled()
    // One court on its own still has its own slots.
    await page.getByRole('button', { name: 'Badminton 1', exact: true }).click()
    await expect(page.getByRole('button', { name: /^8–9 PM/ })).toBeDisabled()
    await expect(page.getByRole('button', { name: /^9–10 PM/ })).toBeEnabled()
    await logout(page)
  })

  await test.step('owner: today at a glance, two-tap walk-ins, phone booking, balance, no-show', async () => {
    await login(page, demoOwner.phone, demoOwner.password)
    await expect(page).toHaveURL(/\/owner/)
    await expect(page.getByRole('heading', { name: 'Today' })).toBeVisible()
    await shot(page, '11-owner-today')
    // One column per court; these steps use the football turf's.
    const turf = page.getByRole('group', { name: 'Main turf' })
    await expect(page.getByRole('group', { name: 'Badminton 2' })).toBeVisible()

    // Two taps: the slot, then Walk-in. The day's earliest free slots have already finished.
    const first = await freeSlot(turf, 0)
    await turf.getByRole('button', { name: new RegExp(`^${escape(first)}.*Add$`) }).click()
    await shot(page, '12-quick-entry')
    await page.getByRole('dialog').getByRole('button', { name: 'Walk-in' }).click()
    await expect(page.getByText(`Walk-in booked for ${first}.`)).toBeVisible()

    const second = await freeSlot(turf, 0)
    await turf.getByRole('button', { name: new RegExp(`^${escape(second)}.*Add$`) }).click()
    await page.getByRole('dialog').getByLabel('Name').fill('Rohit')
    await page.getByRole('dialog').getByRole('button', { name: 'Walk-in' }).click()
    await expect(page.getByText(`Rohit booked for ${second}.`)).toBeVisible()

    const last = await turf.getByRole('button', { name: /Add$/ }).last().locator('span').first().textContent()
    await turf.getByRole('button', { name: /Add$/ }).last().click()
    await page.getByRole('dialog').getByLabel('Name').fill('Imtiyaz')
    await page.getByRole('dialog').getByLabel('Phone').fill('9822012345')
    await page.getByRole('dialog').getByRole('button', { name: 'Phone' }).click()
    await expect(page.getByText(`Imtiyaz booked for ${last!.trim()}.`)).toBeVisible()

    await turf.getByRole('button', { name: new RegExp(`^${escape(first)}.*Walk-in`) }).click()
    await shot(page, '13-booking-actions')
    await page.getByRole('dialog').getByRole('button', { name: 'UPI' }).click()
    await expect(page.getByText(/received by UPI\./)).toBeVisible()
    await expect(turf.getByRole('button', { name: new RegExp(`^${escape(first)}.*Paid · UPI`) })).toBeVisible()

    await turf.getByRole('button', { name: new RegExp(`^${escape(second)}.*Rohit`) }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Mark no-show' }).click()
    await expect(page.getByText('Marked as no-show.')).toBeVisible()
    await shot(page, '14-owner-after-actions')
  })

  await test.step('owner: cancel a player booking for rain', async () => {
    await page.goto(`/owner?date=${day(1)}`)
    await page.getByRole('button', { name: /^6–7 PM.*Rahul Patil/ }).click()
    const sheet = page.getByRole('dialog')
    await sheet.getByRole('button', { name: 'Cancel booking' }).click()
    await sheet.getByRole('button', { name: 'Rain' }).click()
    await sheet.getByLabel(/Note for the player/).fill('Waterlogged pitch')
    await shot(page, '15-owner-cancel')
    await sheet.getByRole('button', { name: 'Cancel and refund' }).click()
    await expect(page.getByText(/Booking cancelled\. ₹200 refunded to the player/)).toBeVisible()
  })

  await test.step('owner: regulars and waiver', async () => {
    await page.goto('/owner/regulars')
    const row = page.getByRole('listitem').filter({ hasText: player.phone })
    await row.getByRole('switch').click()
    await expect(page.getByText(`${player.name} can now book without an advance.`)).toBeVisible()
    await expect(page.getByText('Advance waived')).toBeVisible()
    await shot(page, '16-regulars')

    // A regular slot every <tomorrow's weekday> at 8 PM: four weeks of bookings, then end it.
    const tomorrow = new Date(Date.now() + 330 * 60_000 + 86_400_000)
    const weekday = tomorrow.toLocaleDateString('en-US', { weekday: 'long', timeZone: 'UTC' })
    await page.getByRole('button', { name: 'Add regular slot' }).click()
    const sheet = page.getByRole('dialog')
    await sheet.getByRole('button', { name: new RegExp(`·${player.phone.slice(-4)}`) }).click()
    await sheet.getByRole('button', { name: weekday }).click()
    await expect(sheet.getByText(/· 8–9 PM/)).toBeVisible()
    await shot(page, '16b-regular-slot')
    await sheet.getByRole('button', { name: 'Save regular slot' }).click()
    await expect(page.getByText(/Saved\. \d+ bookings? made/)).toBeVisible()
    const slotCard = page.getByRole('listitem').filter({ hasText: /Every \w+ · 8–9 PM/ })
    await expect(slotCard.getByText(/\d+ booked ahead/)).toBeVisible()
    await shot(page, '16c-regular-slots')
    await slotCard.getByRole('button', { name: 'End' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'End regular slot' }).click()
    await expect(page.getByText(/Regular slot ended\./)).toBeVisible()

    await page.goto('/owner/venue')
    await expect(page.getByRole('heading', { name: 'Courts' })).toBeVisible()
    await page.getByRole('button', { name: /^Main turf/ }).click()
    await expect(page.getByText('Every opening hour has a price.')).toBeVisible()
    await shot(page, '17-turf-settings')
    await logout(page)
  })

  await test.step('player sees the reason; waived regular books with no advance', async () => {
    await login(page, player.phone, player.password)
    await page.goto('/bookings')
    await page.getByRole('tab', { name: /Past/ }).click()
    await expect(page.getByText('Cancelled by the venue: Rain · Waterlogged pitch')).toBeVisible()
    await shot(page, '18-player-sees-reason')

    await page.goto(`/venues/${venueId}?date=${day(4)}`)
    await page.getByRole('button', { name: /^8–9 PM/ }).click()
    await page.getByRole('button', { name: 'Hold this slot' }).click()
    await expect(page.getByText('No advance needed: you are a regular here.')).toBeVisible()
    await shot(page, '19-waived-ticket')
    await logout(page)
  })

  await test.step('new owner sets up a venue with its first court; it waits for approval', async () => {
    await page.goto('/login')
    await page.getByRole('tab', { name: 'Create account' }).click()
    await page.getByLabel('Your name').fill(owner2.name)
    await page.getByLabel('Mobile number').fill(owner2.phone)
    await page.getByLabel('Password').fill(owner2.password)
    await page.getByRole('switch', { name: 'I run a venue' }).click()
    await page.getByRole('main').getByRole('button', { name: 'Create account' }).click()
    await page.getByRole('button', { name: 'Set up my venue' }).click()

    await page.getByLabel('Venue name').fill(`Goal Box ${run}`)
    await page.getByLabel('Area').fill('Osmanpura')
    await page.getByLabel('Address').fill('Near Osmanpura Circle, Aurangabad')
    await shot(page, '20-turf-setup')
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page.getByText('Waiting for approval')).toBeVisible()
    await shot(page, '21-new-owner-pending')
    await logout(page)

    await page.goto('/')
    await expect(page.getByText(`Goal Box ${run}`)).toHaveCount(0)
  })

  await test.step('admin approves; the venue appears for players', async () => {
    await login(page, admin.phone, admin.password)
    await expect(page).toHaveURL(/\/admin/)
    const card = page.getByRole('article').filter({ hasText: `Goal Box ${run}` })
    await expect(card.getByText(`${owner2.name} · ${owner2.phone}`)).toBeVisible()
    await shot(page, '22-admin-pending')
    await card.getByRole('button', { name: 'Approve' }).click()
    await expect(page.getByText(`Goal Box ${run} is now visible to players.`)).toBeVisible()
    await logout(page)

    await page.goto('/?area=Osmanpura')
    await expect(page.getByText(`Goal Box ${run}`)).toBeVisible()
    await shot(page, '23-explore-after-approval')
  })
})
