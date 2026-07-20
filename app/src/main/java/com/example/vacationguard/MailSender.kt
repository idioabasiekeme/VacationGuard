package com.example.vacationguard

import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Sends alert emails (with the recorded footage attached) through Gmail
 * SMTP. The sender account needs an "App password" (Google Account ->
 * Security -> 2-Step Verification -> App passwords). Call from a
 * background thread only.
 */
object MailSender {

    fun send(
        from: String,
        appPassword: String,
        to: String,
        subject: String,
        body: String,
        attachment: File?
    ) {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(from, appPassword)
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject)
        }

        val multipart = MimeMultipart()
        multipart.addBodyPart(MimeBodyPart().apply { setText(body) })
        if (attachment != null && attachment.exists()) {
            multipart.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(FileDataSource(attachment))
                fileName = attachment.name
            })
        }
        message.setContent(multipart)
        Transport.send(message)
    }
}
