import { expect, test, type Page } from '@playwright/test'

// The demo tournament (DemoTournament: TurfUp Cricket Cup, matches 1-2 played, 3-7 to play) on the e2e stack.
const SHOTS = process.env.SHOTS ?? 'test-results/shots'
const scorer = { phone: '7777777777', password: 'scorer-demo-123' }

async function shot(page: Page, name: string) {
  await page.screenshot({ path: `${SHOTS}/${name}.png`, animations: 'disabled' })
}

const pad = (page: Page, name: string) => page.getByRole('main').getByRole('button', { name, exact: true })
const scorecard = (page: Page) => page.getByRole('main').locator('section').first().innerText()
const waiting = (page: Page) =>
  page.evaluate(() => JSON.parse(localStorage.getItem(`turfup.ballQueue.${location.pathname.split('/').pop()}`) ?? '[]').length)

test('tournament: the public pages, then a match scored through a dead zone and followed live', async ({ page, browser }) => {
  let matchUrl = ''

  await test.step('a visitor finds the tournament: results, points table, leaderboards, a scorecard', async () => {
    await page.goto('/')
    await page.getByRole('link', { name: /Live cricket · TurfUp Cricket Cup/ }).click()
    await expect(page.getByRole('heading', { name: 'TurfUp Cricket Cup' })).toBeVisible()
    await expect(page.getByText(/won by|Match tied/)).toHaveCount(2) // matches 1 and 2 were played
    await shot(page, 't01-matches')

    await page.getByRole('tab', { name: 'Table' }).click()
    const rows = page.getByRole('table').getByRole('row')
    await expect(rows).toHaveCount(5) // header + 4 teams
    await expect(page.getByText(/NRR: runs scored per over/)).toBeVisible()
    await shot(page, 't02-table')

    await page.getByRole('tab', { name: 'Leaders' }).click()
    await expect(page.getByRole('heading', { name: 'Most runs' })).toBeVisible()
    await shot(page, 't03-leaders')

    await page.getByRole('tab', { name: 'Matches' }).click()
    await page.getByRole('link', { name: /Match 1/ }).click()
    await expect(page.getByRole('heading', { name: /innings/ })).toHaveCount(2)
    await shot(page, 't04-scorecard')
  })

  await test.step('the scorer starts match 3 and a visitor follows it live', async () => {
    await page.goto('/login')
    await page.getByLabel('Mobile number').fill(scorer.phone)
    await page.getByLabel('Password').fill(scorer.password)
    await page.getByRole('main').getByRole('button', { name: 'Log in' }).click()
    await expect(page).not.toHaveURL(/\/login/)
    await page.goto('/score')
    await page.getByRole('link', { name: /Match 3/ }).click()
    matchUrl = page.url()
    await page.getByRole('button', { name: 'Cidco Strikers' }).click()
    await page.getByRole('button', { name: 'Bat' }).click()
    await page.getByRole('button', { name: 'Save the toss' }).click()
    const pick = (label: string, name: string) =>
      page.getByText(label, { exact: true }).locator('xpath=following-sibling::div[1]').getByRole('button', { name }).click()
    await pick('On strike', 'Rahul Patil')
    await pick('Other end', 'Aditya Kulkarni')
    await pick('Opening bowler', 'Arbaaz Shaikh')
    await page.getByRole('button', { name: 'Start', exact: true }).click()
    await pad(page, '4').click()
    await pad(page, '1').click()
    await expect(page.getByText('5/0')).toBeVisible()
  })

  // A visitor with no account, on another phone.
  const visitor = await (await browser.newContext()).newPage()
  const matchId = matchUrl.split('/').pop()

  await test.step('the live page shows the score', async () => {
    await visitor.goto(`/m/${matchId}`)
    await expect(visitor.getByText('CID 5/0')).toBeVisible()
    await shot(visitor, 't05-live')
  })

  await test.step('the signal drops: the scorer keeps scoring, the balls wait on the phone', async () => {
    await page.context().setOffline(true)
    await pad(page, 'Wd').click()
    await page.getByRole('dialog').getByRole('button', { name: '0', exact: true }).click()
    await expect(page.getByText(/No signal/)).toBeVisible()
    await pad(page, '6').click()
    await pad(page, 'Out').click()
    await page.getByRole('dialog').getByRole('button', { name: 'Caught' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Faizan Qureshi' }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Out', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Omkar Pawar' }).click()
    await pad(page, '2').click()
    await expect(page.getByText('14/1')).toBeVisible()
    expect(await waiting(page)).toBe(4)
    // The visitor still sees what the server has.
    await expect(visitor.getByText('CID 5/0')).toBeVisible()
  })

  await test.step('the signal returns: the balls send themselves and both phones agree', async () => {
    const offlineCard = await scorecard(page)
    await page.context().setOffline(false)
    await expect.poll(() => waiting(page), { timeout: 30_000 }).toBe(0)
    await page.reload()
    await expect(page.getByText('14/1')).toBeVisible()
    expect(await scorecard(page)).toBe(offlineCard)
    await expect(visitor.getByText('CID 14/1')).toBeVisible({ timeout: 15_000 }) // the live page refreshes itself
    await expect(visitor.getByText(/c Qureshi b Shaikh/)).toBeVisible()
    await shot(visitor, 't06-live-after-sync')
  })

  await test.step('the over ends: the next bowler is picked and the live page follows', async () => {
    await pad(page, '0').click() // the 6th legal ball
    await expect(page.getByRole('dialog').getByText(/Who bowls it/)).toBeVisible()
    await expect(page.getByRole('dialog').getByRole('button', { name: 'Arbaaz Shaikh' })).toHaveCount(0)
    await page.getByRole('dialog').getByRole('button', { name: 'Faizan Qureshi' }).click()
    await pad(page, '1').click()
    await expect(visitor.getByText('CID 15/1')).toBeVisible({ timeout: 15_000 })
    await expect(visitor.getByText('(1.1 ov)').first()).toBeVisible()
  })
})

test('tournament admin: a tournament set up from the screens, then a match moved and abandoned', async ({ page }) => {
  // Ten days ahead (IST), after the demo tournament, which the test above relies on being first.
  const day = new Date(Date.now() + 330 * 60_000 + 10 * 86_400_000).toISOString().slice(0, 10)
  const dialog = page.getByRole('dialog')

  await test.step('the admin creates the tournament with its rules', async () => {
    await page.goto('/login')
    await page.getByLabel('Mobile number').fill('9999999999')
    await page.getByLabel('Password').fill('admin-demo-123')
    await page.getByRole('main').getByRole('button', { name: 'Log in' }).click()
    await page.getByRole('link', { name: 'Cricket' }).click()
    await expect(page.getByRole('link', { name: /TurfUp Cricket Cup/ })).toBeVisible()

    await page.getByRole('button', { name: 'New tournament' }).click()
    await dialog.getByLabel('Name').fill('Garkheda Night League')
    await dialog.getByLabel('Where').fill('Street Sports Arena, Garkheda')
    await dialog.getByLabel('First day').fill(day)
    await dialog.getByLabel('Last day').fill(day)
    await dialog.getByLabel('Overs per innings').fill('5')
    await dialog.getByLabel('Free hit after a no-ball').check()
    await shot(page, 'a01-new-tournament')
    await dialog.getByRole('button', { name: 'Create tournament' }).click()
    await expect(page).toHaveURL(/\/admin\/tournaments\/\d+$/)
    await expect(page.getByRole('heading', { name: 'Garkheda Night League' })).toBeVisible()
  })

  await test.step('two teams from pasted squads', async () => {
    const squads = {
      Lions: ['7 Arjun Jadhav (c)', '10. Kunal More', 'Nikhil Gaikwad', '  Pratik   Jadhav ', '', 'Sagar Wagh', '#3 Tushar Kale', 'Yash Deshmukh', 'Mahesh Rao'],
      Tigers: ['Irfan Sayyed (c)', 'Junaid Khan', 'Wasim Patel', 'Salman Shaikh', 'Asif Inamdar', 'Rehan Siddiqui', 'Owais Kazi', 'Danish Ansari'],
    }
    for (const [name, lines] of Object.entries(squads)) {
      await page.getByRole('button', { name: 'Add team' }).click()
      await dialog.getByLabel('Team name').fill(name)
      await dialog.getByLabel(/Short name/).fill(name.slice(0, 3).toUpperCase())
      await dialog.getByLabel('Squad').fill(lines.join('\n'))
      await expect(dialog.getByText(/^8 players · captain (Arjun Jadhav|Irfan Sayyed)$/)).toBeVisible()
      if (name === 'Lions') await shot(page, 'a02-paste-squad')
      await dialog.getByRole('button', { name: 'Add team' }).click()
      await expect(dialog).toBeHidden()
    }
    await expect(page.getByText('8 players · captain Arjun Jadhav')).toBeVisible()
    await expect(page.getByText('8 players · captain Irfan Sayyed')).toBeVisible()
  })

  await test.step('a group match with the demo scorer, and a final whose teams aren\'t known yet', async () => {
    await page.getByRole('button', { name: 'Add match' }).click()
    await expect(dialog.getByLabel('Team A')).toHaveValue(/\d+/) // Lions and Tigers picked already
    await dialog.getByLabel('Date and time (IST)').fill(`${day}T19:00`)
    await dialog.getByLabel('Scorer’s mobile number').fill(scorer.phone)
    await dialog.getByRole('button', { name: 'Add match' }).click()
    await expect(dialog).toBeHidden()

    await page.getByRole('button', { name: 'Add match' }).click()
    await dialog.getByLabel('Stage').selectOption('FINAL')
    await dialog.getByLabel('Team A', { exact: true }).selectOption('')
    await dialog.getByLabel('Team A: Label until it is known').fill('1st in the table')
    await dialog.getByLabel('Team B', { exact: true }).selectOption('')
    await dialog.getByLabel('Team B: Label until it is known').fill('2nd in the table')
    await dialog.getByRole('button', { name: 'Add match' }).click()
    await expect(dialog).toBeHidden()

    const match1 = page.getByRole('listitem').filter({ hasText: 'Match 1' })
    await expect(match1).toContainText('Lions vs Tigers')
    await expect(match1).toContainText('7 PM')
    await expect(match1).toContainText(`· ${scorer.phone}`)
    await expect(page.getByRole('listitem').filter({ hasText: 'Match 2' })).toContainText('1st in the table vs 2nd in the table')
    await shot(page, 'a03-matches')
  })

  await test.step('rain: match 1 moves to 8 PM, then is abandoned', async () => {
    const match1 = page.getByRole('listitem').filter({ hasText: 'Match 1' })
    await match1.getByRole('button', { name: 'Edit' }).click()
    await dialog.getByLabel('Date and time (IST)').fill(`${day}T20:00`)
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(dialog).toBeHidden()
    await expect(match1).toContainText('8 PM')

    await match1.getByRole('button', { name: 'Edit' }).click()
    await dialog.getByRole('button', { name: 'Abandon the match' }).click()
    await dialog.getByRole('button', { name: 'Abandon', exact: true }).click()
    await expect(dialog).toBeHidden()
    await expect(match1.getByText('Abandoned', { exact: true })).toBeVisible()
    await shot(page, 'a04-abandoned')
  })

  await test.step('the public page shows it: no result, a point each', async () => {
    await page.getByRole('link', { name: 'Public page' }).first().click()
    await expect(page).toHaveURL(/\/t\/\d+$/)
    await page.getByRole('tab', { name: 'Table' }).click()
    const rows = page.getByRole('table').getByRole('row')
    await expect(rows).toHaveCount(3)
    await expect(rows.nth(1)).toContainText(/1\s*0\s*0\s*1\s*1/) // P W L NR Pts
  })
})
