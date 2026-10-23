import type { Business } from '../lib/business'

/**
 * The Terms, Privacy, Refund & Cancellation and Contact pages. Kept apart from en.ts because they are long;
 * a translation adds policies.mr.ts / policies.hi.ts beside this file.
 *
 * The refund page must match the real rules in CLAUDE.md (Cancellation, Holds, Advance payment). Change
 * both together.
 */
export interface PolicySection {
  heading: string
  paragraphs: string[]
}

export interface Policy {
  title: string
  intro: string
  sections: PolicySection[]
}

export function policies(b: Business): Record<'terms' | 'privacy' | 'refunds' | 'contact', Policy> {
  return {
    terms: {
      title: 'Terms of use',
      intro: `These terms apply when you use TurfUp to find and book sports venues (turfs and courts) in Aurangabad, or to list and manage a venue. TurfUp is operated by ${b.legalName} ("we"). By creating an account or making a booking you agree to them.`,
      sections: [
        {
          heading: 'What TurfUp does',
          paragraphs: [
            'TurfUp shows venues, their courts (a football turf, a badminton court and so on), their open slots and prices, and lets you hold a slot on a court, pay an advance and book it. The venue itself is run by its owner, who is responsible for the ground, the equipment, safety and the game. We are not a party to the game you play.',
            'Venue owners list their venues and courts, set their hours, prices, advance amounts and refund windows, and record walk-in and phone bookings. A new venue appears to players only after we approve it.',
          ],
        },
        {
          heading: 'Your account',
          paragraphs: [
            'You sign up with your name and mobile number. Keep your password private; you are responsible for bookings made from your account. One person, one account.',
            'We may suspend an account that is used to block slots without paying, to harass owners or players, or to break the law.',
          ],
        },
        {
          heading: 'Booking and payment',
          paragraphs: [
            'When you start a booking, the slot is held for you for a few minutes (5 by default) while you pay. If the hold runs out before your payment is confirmed, the slot is released.',
            'Most courts ask for an advance (₹200 unless the owner sets another amount for that court) to confirm the booking. The price shown when you book is the price you pay; later price changes do not affect your booking. The balance is paid to the owner at the venue.',
            'Online payments are processed by Razorpay. The advance is paid to the venue owner through Razorpay; TurfUp does not hold it. We never see or store your card, UPI PIN or bank details.',
            'A booking is confirmed only when the payment is confirmed to us by Razorpay. If a payment arrives after your hold has run out and the slot is no longer yours, it is refunded automatically.',
          ],
        },
        {
          heading: 'Cancellations and refunds',
          paragraphs: [
            'Cancellations and refunds follow our Refund & Cancellation policy, which is part of these terms. Each court also shows its own refund window before you pay.',
          ],
        },
        {
          heading: 'At the venue',
          paragraphs: [
            'Arrive on time; the slot ends when it is booked to end. Follow the venue\'s rules. If you do not turn up, the owner may mark the booking as a no-show; the advance is not refunded and the owner may stop waiving the advance for you.',
          ],
        },
        {
          heading: 'Venue owners',
          paragraphs: [
            'Owners must give true details about their venue and courts, keep prices and hours accurate, honour confirmed bookings, and refund players as our Refund & Cancellation policy requires when they cancel. Owners receive advances in their own Razorpay linked account and are responsible for their own taxes.',
          ],
        },
        {
          heading: 'Liability',
          paragraphs: [
            'We work to keep TurfUp available and correct, but we cannot promise it will never be down or wrong. To the extent the law allows, our liability for any booking is limited to the amount you paid through TurfUp for that booking. We are not liable for injury, loss or damage at a venue; that is between you and the venue owner.',
          ],
        },
        {
          heading: 'Changes and disputes',
          paragraphs: [
            'We may update these terms; the date at the top shows the latest version, and continued use means you accept it. These terms are governed by the laws of India, and the courts at ' + b.jurisdiction + ' have jurisdiction.',
          ],
        },
      ],
    },

    privacy: {
      title: 'Privacy policy',
      intro: `This policy explains what personal data TurfUp collects, why, and what you can do about it. ${b.legalName} is responsible for this data under the Digital Personal Data Protection Act, 2023 and the Information Technology Act, 2000.`,
      sections: [
        {
          heading: 'What we collect',
          paragraphs: [
            'Account: your name, mobile number, a password (stored only as a one-way hash), and your email if you give it.',
            'Bookings: the venues, courts, dates and times you book, what you paid, cancellations and their reasons, and whether you turned up.',
            'Payments: Razorpay\'s order and payment references and amounts. Your card, UPI and bank details go to Razorpay, not to us.',
            'Venue owners: venue and court details, contact details, and the Razorpay linked account that receives advances.',
            'Technical: error reports (the page or request that failed and your browser type) so we can fix problems. These do not include your password or payment details.',
          ],
        },
        {
          heading: 'Why we use it',
          paragraphs: [
            'To run your bookings: to show the owner who booked, let them contact you about the booking, process payments and refunds, and keep records the law requires. We do not sell your data, and we do not use it for advertising.',
          ],
        },
        {
          heading: 'Who sees it',
          paragraphs: [
            'The owner of a venue you book sees your name, mobile number and your booking history at that venue (for example, how often you play and any no-shows).',
            'Razorpay processes payments under its own privacy policy. Our hosting and error-reporting providers process data only to provide their service to us. We disclose data to authorities only when the law requires it.',
          ],
        },
        {
          heading: 'How long we keep it',
          paragraphs: [
            'We keep your account while it is open. Booking and payment records are kept for 8 years because tax and accounting law requires it; other data is deleted or anonymised within 90 days of closing your account.',
          ],
        },
        {
          heading: 'Your rights',
          paragraphs: [
            'You can ask us for a summary of your data, ask us to correct it, or ask us to delete your account (records we must keep by law are kept for the required period). You can withdraw consent by deleting your account. Write to the grievance officer below; we reply within 30 days.',
          ],
        },
        {
          heading: 'Security',
          paragraphs: [
            'Data travels over HTTPS, passwords are hashed, and access to the database is restricted. If a breach affects you, we will tell you and the Data Protection Board as the law requires.',
          ],
        },
        {
          heading: 'Grievance officer',
          paragraphs: [
            `${b.grievanceOfficer.name}, ${b.grievanceOfficer.email}. ${b.legalName}, ${b.address}.`,
          ],
        },
      ],
    },

    refunds: {
      title: 'Refund & cancellation policy',
      intro: 'How cancellations and refunds work on TurfUp. Each court shows its own advance and refund window on the venue page and again before you pay, so you always know what applies before you book.',
      sections: [
        {
          heading: 'The advance',
          paragraphs: [
            'Most courts ask for an advance to confirm a booking: ₹200 unless the owner sets a different amount for that court. The rest of the price is paid at the venue. Some owners waive the advance for their regular players; a waiver applies on every court of that venue, and those bookings are confirmed without any online payment.',
          ],
        },
        {
          heading: 'When you cancel',
          paragraphs: [
            'You get the full advance back if both of these are true: you cancel at least the court\'s refund window before the slot starts (24 hours unless the owner sets another number for that court), and you have not already had a cancellation refund at that venue this calendar month, on any of its courts. Each venue allows one such refund per player per month unless it sets a different number.',
            'If you cancel later than that, or you have already used this month\'s refund at that venue, the booking is cancelled without a refund. A late cancellation does not use up your monthly refund.',
            'Before you confirm a cancellation, the app shows whether you will get a refund. If that changes while you are deciding (for example, the window passes), nothing is cancelled and you are shown the new outcome to confirm again.',
          ],
        },
        {
          heading: 'When the venue cancels',
          paragraphs: [
            'If the owner cancels your booking (for rain, maintenance or another reason), you always get back everything you paid: the advance, and any balance you already paid at the venue. These refunds never count toward your monthly limit. You see the reason in My bookings.',
          ],
        },
        {
          heading: 'Payments that arrive too late',
          paragraphs: [
            'A slot is held for a few minutes while you pay. If your payment is confirmed after the hold has run out and the slot cannot be kept for you, the full amount is refunded automatically and the app tells you.',
            'Releasing a slot you are holding, before paying, is not a cancellation: nothing is charged.',
          ],
        },
        {
          heading: 'No-shows',
          paragraphs: [
            'If you do not turn up for a confirmed booking, the advance is not refunded.',
          ],
        },
        {
          heading: 'How refunds reach you',
          paragraphs: [
            'Online refunds go back to the card, UPI account or bank account you paid from, through Razorpay. They are started at once; banks usually show them within 5 to 7 working days. Money you paid at the venue is refunded by the owner at the venue.',
            `If a refund has not reached you after 7 working days, contact us at ${b.email} with your booking details.`,
          ],
        },
      ],
    },

    contact: {
      title: 'Contact us',
      intro: 'Questions about a booking, a refund, or listing your venue? Reach us here.',
      sections: [
        {
          heading: 'Support',
          paragraphs: [`Email: ${b.email}`, `Phone: ${b.phone} (${b.supportHours})`],
        },
        {
          heading: 'Address',
          paragraphs: [`${b.legalName}`, b.address],
        },
        {
          heading: 'Grievance officer',
          paragraphs: [`${b.grievanceOfficer.name}, ${b.grievanceOfficer.email}`],
        },
        {
          heading: 'About a booking',
          paragraphs: [
            'Questions about the ground or the balance are best asked at the venue. When you write to us about a booking, include the venue, court, date and time.',
          ],
        },
      ],
    },
  }
}
