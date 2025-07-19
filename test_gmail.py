import smtplib
from email.mime.text import MIMEText

# Gmail credentials
gmail_user = 'info@be-working.com'
gmail_password = 'pvhpgdidswrfqqph'  # App Password, no spaces

# Email details
to = 'info@be-working.com'  # You can use your own email for testing
subject = 'SMTP Test'
body = 'This is a test email from Python.'

msg = MIMEText(body)
msg['Subject'] = subject
msg['From'] = gmail_user
msg['To'] = to

try:
    server = smtplib.SMTP('smtp.gmail.com', 587)
    server.ehlo()
    server.starttls()
    server.login(gmail_user, gmail_password)
    server.sendmail(gmail_user, [to], msg.as_string())
    server.quit()
    print('Email sent successfully!')
except Exception as e:
    print('Failed to send email:', e)
